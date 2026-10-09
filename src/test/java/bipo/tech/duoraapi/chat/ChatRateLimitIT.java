package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.RateLimitTestSupport.bucketKeysOf;
import static bipo.tech.duoraapi.RateLimitTestSupport.expectRejectedByTheLimit;
import static bipo.tech.duoraapi.RateLimitTestSupport.expectUnavailableBecauseTheLimitCannotBeCounted;
import static bipo.tech.duoraapi.RateLimitTestSupport.whileTheLimitCannotBeCounted;
import static bipo.tech.duoraapi.chat.ChatFixtures.chatPath;
import static bipo.tech.duoraapi.chat.ChatFixtures.messagesPath;
import static bipo.tech.duoraapi.chat.ChatFixtures.newKey;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Limite por conta do envio de mensagens (docs/adr/0021, STRIDE: Denial of service): cada envio trava o chat
 * e grava, mesmo quando só devolve a mensagem da mesma Idempotency-Key. A capacidade é reduzida a 3 por minuto
 * para chegar logo ao fim; o valor de produção (20 por minuto) vem de application.properties.
 */
@SpringBootTest(properties = {
        "duora.chat.message-rate-limit.capacity=" + ChatRateLimitIT.CAPACITY,
        "duora.chat.message-rate-limit.period=PT1M"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class ChatRateLimitIT {

    static final int CAPACITY = 3;

    /** Uma ficha de volta a cada 1 min / 3 = 20 s. */
    private static final String SECONDS_TO_NEXT_CALL = "20";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @Test
    void sendsAboveTheLimitAreRejectedWithRetryAfterAndWithoutWriting() throws Exception {
        String eventId = paired("ana", "bruno");
        for (int i = 0; i < CAPACITY; i++) {
            send(eventId, "ana", newKey()).andExpect(status().isCreated());
        }

        expectRejectedByTheLimit(send(eventId, "ana", newKey()),
                SECONDS_TO_NEXT_CALL, messagesPath(eventId, 1));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isEqualTo(CAPACITY);
    }

    /** O custo é o lock e a transação, e não o efeito: a repetição com a mesma chave também gasta. */
    @Test
    void repeatsWithTheSameKeySpendTheLimit() throws Exception {
        String eventId = paired("ana", "bruno");
        String key = newKey();
        send(eventId, "ana", key).andExpect(status().isCreated());
        send(eventId, "ana", key).andExpect(status().isOk());
        send(eventId, "ana", key).andExpect(status().isOk());

        send(eventId, "ana", key).andExpect(status().isTooManyRequests());
    }

    /** Quem não formou par também gasta: limita quem tenta adivinhar ids de evento e números de rodada. */
    @Test
    void sendsFromWhoFormedNoPairSpendTheLimit() throws Exception {
        String eventId = paired("ana", "bruno");
        mockMvc.perform(get("/api/me").with(user("carla"))).andExpect(status().isOk());
        for (int i = 0; i < CAPACITY; i++) {
            send(eventId, "carla", newKey()).andExpect(status().isNotFound());
        }

        send(eventId, "carla", newKey()).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitIsCountedForEachAccountSeparately() throws Exception {
        String eventId = paired("ana", "bruno");
        for (int i = 0; i < CAPACITY; i++) {
            send(eventId, "ana", newKey()).andExpect(status().isCreated());
        }
        send(eventId, "ana", newKey()).andExpect(status().isTooManyRequests());

        send(eventId, "bruno", newKey()).andExpect(status().isCreated());
    }

    /** Texto inválido é recusado antes de tocar o banco: não gasta nem abre bucket. */
    @Test
    void sendsRefusedBeforeTheDatabaseDoNotSpendTheLimit() throws Exception {
        String eventId = paired("ana", "bruno");
        for (int i = 0; i <= CAPACITY; i++) {
            ChatFixtures.send(mockMvc, eventId, user("ana"), newKey(), "a".repeat(501))
                    .andExpect(status().isBadRequest());
        }

        assertThat(keysOfTheLimit()).isEmpty();
        send(eventId, "ana", newKey()).andExpect(status().isCreated());
    }

    /** O polling lê pela chave primária: não gasta o limite (docs/adr/0021, "Limites e rate limit"). */
    @Test
    void readingTheChatDoesNotSpendTheLimit() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "ana", newKey()).andExpect(status().isCreated());
        for (int i = 0; i < CAPACITY * 2; i++) {
            mockMvc.perform(get(chatPath(eventId, 1)).with(user("ana"))).andExpect(status().isOk());
            mockMvc.perform(get(messagesPath(eventId, 1)).with(user("ana"))).andExpect(status().isOk());
        }

        send(eventId, "ana", newKey()).andExpect(status().isCreated());
    }

    @Test
    void theBucketLivesUnderTheChatKeyOfTheAccount() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "ana", newKey()).andExpect(status().isCreated());

        assertThat(keysOfTheLimit()).containsExactly("chat:" + ChatFixtures.accountOf(jdbcClient, "ana"));
    }

    /** Falha fechada (docs/adr/0006): sem contar o limite, o envio é recusado, e nada é gravado. */
    @Test
    void sendIsRefusedWhenTheLimitCannotBeCounted() throws Exception {
        String eventId = paired("ana", "bruno");
        whileTheLimitCannotBeCounted(jdbcClient, () -> expectUnavailableBecauseTheLimitCannotBeCounted(
                send(eventId, "ana", newKey()),
                messagesPath(eventId, 1)));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    private String paired(String... names) throws Exception {
        return ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, names);
    }

    private ResultActions send(String eventId, String name, String key) throws Exception {
        return ChatFixtures.send(mockMvc, eventId, user(name), key, "oi");
    }

    private List<String> keysOfTheLimit() {
        return bucketKeysOf(jdbcClient, "chat");
    }

}
