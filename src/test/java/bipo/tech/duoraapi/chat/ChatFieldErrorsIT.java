package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.chat.ChatFixtures.IDEMPOTENCY_KEY;
import static bipo.tech.duoraapi.chat.ChatFixtures.messagesPath;
import static bipo.tech.duoraapi.chat.ChatFixtures.newKey;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * O 400 do corpo da mensagem diz qual campo falhou e por quê em {@code errors} (docs/adr/0018), sem repetir o
 * texto recebido. O par existe e o chat está aberto: sem a validação, cada corpo abaixo gravaria uma mensagem.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class ChatFieldErrorsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    private String eventId;

    @BeforeEach
    void pairInRoundOne() throws Exception {
        EventFixtures.cleanDatabase(jdbcClient);
        eventId = ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, "ana", "bruno");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String detail, String errors) throws Exception {
        send(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "%s", "instance": "%s", "errors": %s}
                        """.formatted(detail, messagesPath(eventId, 1), errors), JsonCompareMode.STRICT));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("sem texto", "{}", "Invalid request content.", "text", "REQUIRED"),
                invalid("texto nulo", "{\"text\": null}", "Invalid request content.", "text", "REQUIRED"),
                invalid("texto em branco", "{\"text\": \"  \\n \"}", "text must have 1 to 500 characters", "text",
                        "TOO_SHORT"),
                invalid("texto com 501 caracteres", "{\"text\": \"" + "a".repeat(501) + "\"}",
                        "text must have 1 to 500 characters", "text", "TOO_LONG"),
                invalid("texto com NUL", "{\"text\": \"oi\\u0000\"}", "text contains a forbidden character", "text",
                        "FORBIDDEN_CHARACTER"),
                invalid("texto que é objeto", "{\"text\": {\"value\": \"oi\"}}", "Failed to read request", "text",
                        "INVALID_FORMAT"));
    }

    /** Posição, horário, remetente e chat são do servidor (docs/adr/0021, STRIDE: Tampering). */
    @ParameterizedTest
    @ValueSource(strings = {"seq", "sentAt", "senderAccountId", "fromMe", "chatId", "idempotencyKey"})
    void unknownFieldsAreRejectedWithoutWriting(String field) throws Exception {
        send("{\"text\": \"oi\", \"%s\": \"1\"}".formatted(field))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"errors": [{"field": "%s", "code": "UNKNOWN_FIELD"}]}
                        """.formatted(field)));

        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"text\": \"%s\\u200b\"}", "{\"text\": \"oi\", \"%s\": 1}", "{\"text\": \"%s",
            "{\"text\": \"%s%s\"}"})
    void rejectedValueIsNotEchoed(String bodyTemplate) throws Exception {
        var canary = "CANARY-" + UUID.randomUUID();

        var response = send(bodyTemplate.formatted(canary, "a".repeat(500)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"errors\"").doesNotContain(canary);
        assertThat(ChatFixtures.messageRows(jdbcClient)).isZero();
    }

    private static Arguments invalid(String name, String body, String detail, String field, String code) {
        return Arguments.of(Named.of(name, body), detail, """
                [{"field": "%s", "code": "%s"}]""".formatted(field, code));
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(messagesPath(eventId, 1)).with(user("ana")).header(IDEMPOTENCY_KEY, newKey())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
