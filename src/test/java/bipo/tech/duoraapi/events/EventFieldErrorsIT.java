package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.ProblemJson.strictIgnoringDetail;
import static bipo.tech.duoraapi.events.EventFixtures.ADMIN_EVENTS_PATH;
import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * O 400 do corpo do {@code POST /api/admin/events} diz qual campo falhou e por quê em {@code errors}
 * (docs/adr/0018), sem repetir o valor recebido. O relógio está parado em 2026-10-06T12:00:00Z.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class EventFieldErrorsIT {

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

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String errors) throws Exception {
        create(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "instance": "%s", "errors": %s}
                        """.formatted(ADMIN_EVENTS_PATH, errors), strictIgnoringDetail()));

        assertThat(eventRows()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("sem título", without("title"), "title", "REQUIRED"),
                invalid("título com 81 caracteres", withText("title", "a".repeat(81)), "title", "TOO_LONG"),
                invalid("título em branco", withText("title", "   "), "title", "TOO_SHORT"),
                invalid("título com NUL", withText("title", "Noite\\u0000"), "title", "FORBIDDEN_CHARACTER"),
                invalid("descrição com 501 caracteres", withText("description", "a".repeat(501)),
                        "description", "TOO_LONG"),
                invalid("início sem fuso", withText("startsAt", "2026-11-01T22:00:00"), "startsAt", "INVALID_FORMAT"),
                invalid("sem início", without("startsAt"), "startsAt", "REQUIRED"),
                invalid("início no passado", eventJson("2026-10-06T11:00:00Z", "2026-10-06T13:00:00Z", 10),
                        "startsAt", "BELOW_MINIMUM"),
                invalid("início além de 365 dias", eventJson("2027-10-06T12:00:01Z", "2027-10-06T13:00:00Z", 10),
                        "startsAt", "ABOVE_MAXIMUM"),
                invalid("fim igual ao início", eventJson("2026-11-01T22:00:00Z", "2026-11-01T22:00:00Z", 10),
                        "endsAt", "BELOW_MINIMUM"),
                invalid("duração de 12 horas e 1 segundo", eventJson("2026-11-01T10:00:00Z", "2026-11-01T22:00:01Z", 10),
                        "endsAt", "ABOVE_MAXIMUM"),
                invalid("capacidade 1", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 1),
                        "capacity", "BELOW_MINIMUM"),
                invalid("capacidade 201", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 201),
                        "capacity", "ABOVE_MAXIMUM"),
                invalid("sem capacidade", without("capacity"), "capacity", "REQUIRED"),
                invalid("capacidade em texto", eventJson().replace("\"capacity\": 10", "\"capacity\": \"dez\""),
                        "capacity", "INVALID_FORMAT"),
                invalid("capacidade fracionária", eventJson().replace("\"capacity\": 10", "\"capacity\": 10.5"),
                        "capacity", "INVALID_FORMAT"),
                invalid("estado vindo do cliente", eventJson().replaceFirst("\\{", "{\"status\": \"PUBLISHED\", "),
                        "status", "UNKNOWN_FIELD"),
                Arguments.of(Named.of("JSON quebrado", "{\"title\": "),
                        "[{\"code\": \"MALFORMED_BODY\"}]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "description", "startsAt", "endsAt"})
    void rejectedValueIsNotEchoed(String field) throws Exception {
        var canary = "CANARY-" + UUID.randomUUID();

        var response = create(withText(field, canary + "\\u0000"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"errors\"").doesNotContain(canary);
    }

    private static Arguments invalid(String name, String body, String field, String code) {
        return Arguments.of(Named.of(name, body), """
                [{"field": "%s", "code": "%s"}]""".formatted(field, code));
    }

    private static String withText(String field, String value) {
        return eventJson().replaceFirst("\"" + field + "\": \"[^\"]*\"",
                Matcher.quoteReplacement("\"" + field + "\": \"" + value + "\""));
    }

    private static String without(String field) {
        return eventJson().replaceFirst("\"" + field + "\": (\"[^\"]*\"|\\d+),?", "")
                .replaceAll(",\\s*}", "}");
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post(ADMIN_EVENTS_PATH).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long eventRows() {
        return jdbcClient.sql("select count(*) from event").query(Long.class).single();
    }

}
