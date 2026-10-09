package bipo.tech.duoraapi.matching;

import static bipo.tech.duoraapi.AccountFixtures.accountIdOf;
import static bipo.tech.duoraapi.RateLimitTestSupport.bucketKeysOf;
import static bipo.tech.duoraapi.RateLimitTestSupport.expectRejectedByTheLimit;
import static bipo.tech.duoraapi.RateLimitTestSupport.expectUnavailableBecauseTheLimitCannotBeCounted;
import static bipo.tech.duoraapi.RateLimitTestSupport.whileTheLimitCannotBeCounted;
import static bipo.tech.duoraapi.TestIdentities.ISSUER;
import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.randomId;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Limite por conta ADMIN do sorteio de rodadas (docs/adr/0017, "Rate limit"): cada PUT lê os inscritos e
 * disputa a chave da rodada, mesmo quando só devolve a rodada que já existe. A capacidade é reduzida a 3 por
 * hora para chegar logo ao fim; o valor de produção (30 por hora) vem de application.properties.
 */
@SpringBootTest(properties = {
        "duora.matching.round-rate-limit.capacity=" + RoundRateLimitIT.CAPACITY,
        "duora.matching.round-rate-limit.period=PT1H"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class RoundRateLimitIT {

    static final int CAPACITY = 3;

    /** Uma ficha de volta a cada 1 h / 3 = 1200 s. */
    private static final String SECONDS_TO_NEXT_CALL = "1200";

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
    void callsAboveTheLimitAreRejectedWithRetryAfterAndStartNoRound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        startRound(eventId, 1, admin()).andExpect(status().isCreated());
        startRound(eventId, 1, admin()).andExpect(status().isOk());
        startRound(eventId, 1, admin()).andExpect(status().isOk());

        expectRejectedByTheLimit(startRound(eventId, 2, admin()),
                SECONDS_TO_NEXT_CALL, roundPath(eventId, 2));

        assertThat(roundRows(eventId)).isEqualTo(1);
    }

    /** O custo é ler os inscritos e disputar a chave da rodada, e não o efeito: a repetição também gasta. */
    @Test
    void idempotentRepeatsSpendTheLimit() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        startRound(eventId, 1, admin()).andExpect(status().isCreated());
        startRound(eventId, 1, admin()).andExpect(status().isOk());
        startRound(eventId, 1, admin()).andExpect(status().isOk());

        startRound(eventId, 1, admin()).andExpect(status().isTooManyRequests());
    }

    /** Evento inexistente também gasta: limita quem tenta adivinhar ids de evento. */
    @Test
    void callsForUnknownEventsSpendTheLimit() throws Exception {
        String unknown = randomId();
        for (int i = 0; i < CAPACITY; i++) {
            startRound(unknown, 1, admin()).andExpect(status().isNotFound());
        }

        startRound(unknown, 1, admin()).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitIsCountedForEachAdminAccountSeparately() throws Exception {
        String unknown = randomId();
        for (int i = 0; i < CAPACITY; i++) {
            startRound(unknown, 1, admin()).andExpect(status().isNotFound());
        }
        startRound(unknown, 1, admin()).andExpect(status().isTooManyRequests());

        startRound(unknown, 1, anotherAdmin()).andExpect(status().isNotFound());
    }

    /** Quem não é ADMIN é barrado antes do controller: não gasta limite nem abre bucket. */
    @Test
    void callsRefusedByTheRoleDoNotSpendTheLimit() throws Exception {
        String unknown = randomId();
        for (int i = 0; i <= CAPACITY; i++) {
            startRound(unknown, 1, user("ana")).andExpect(status().isForbidden());
        }

        assertThat(keysOfTheLimit()).isEmpty();
        startRound(unknown, 1, admin()).andExpect(status().isNotFound());
    }

    /** Ler a rodada é uma consulta pela chave primária: não gasta o limite do sorteio. */
    @Test
    void readingARoundDoesNotSpendTheLimit() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        startRound(eventId, 1, admin()).andExpect(status().isCreated());
        for (int i = 0; i < CAPACITY * 2; i++) {
            mockMvc.perform(get(roundPath(eventId, 1)).with(admin())).andExpect(status().isOk());
        }

        startRound(eventId, 1, admin()).andExpect(status().isOk());
    }

    @Test
    void theBucketLivesUnderTheRoundKeyOfTheAdminAccount() throws Exception {
        startRound(randomId(), 1, admin()).andExpect(status().isNotFound());

        String accountId = accountIdOf(jdbcClient, "admin");

        assertThat(keysOfTheLimit()).containsExactly("round:" + accountId);
    }

    /** Falha fechada (docs/adr/0006): sem contar o limite, o sorteio é recusado, e nada é gravado. */
    @Test
    void roundIsRefusedWithoutWritingWhenTheLimitCannotBeCounted() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        whileTheLimitCannotBeCounted(jdbcClient, () -> expectUnavailableBecauseTheLimitCannotBeCounted(
                startRound(eventId, 1, admin()),
                roundPath(eventId, 1)));

        assertThat(roundRows(eventId)).isZero();
    }

    /** Publica um evento, inscreve as pessoas com o perfil completo e leva o relógio ao início. */
    private String underwayEventWith(String... names) throws Exception {
        return RoundFixtures.underwayEventWith(mockMvc, jdbcClient, clock, names);
    }

    private ResultActions startRound(String eventId, int number, RequestPostProcessor caller) throws Exception {
        return mockMvc.perform(put(roundPath(eventId, number)).with(caller));
    }

    private static String roundPath(String eventId, int number) {
        return "/api/admin/events/" + eventId + "/rounds/" + number;
    }

    private long roundRows(String eventId) {
        return jdbcClient.sql("select count(*) from round where event_id = cast(:id as uuid)")
                .param("id", eventId)
                .query(Long.class).single();
    }

    private List<String> keysOfTheLimit() {
        return bucketKeysOf(jdbcClient, "round");
    }

    private static RequestPostProcessor anotherAdmin() {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-admin-2"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

}
