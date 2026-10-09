package bipo.tech.duoraapi.connections;

import static bipo.tech.duoraapi.ProblemJson.strictIgnoringDetail;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * O 400 do corpo da decisão diz qual campo falhou e por quê em {@code errors} (docs/adr/0018), sem repetir o
 * valor recebido. O corpo é lido antes de procurar o par, então qualquer conta serve.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConnectionFieldErrorsIT {

    private static final String PATH = "/api/events/01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b/rounds/1/decision";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String errors) throws Exception {
        decide(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "instance": "%s", "errors": %s}
                        """.formatted(PATH, errors), strictIgnoringDetail()));

        assertThat(jdbcClient.sql("select count(*) from round_decision").query(Long.class).single()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("sem a escolha", "{}", "interested", "REQUIRED"),
                invalid("escolha nula", "{\"interested\": null}", "interested", "REQUIRED"),
                invalid("escolha em texto", "{\"interested\": \"yes\"}", "interested", "INVALID_FORMAT"),
                invalid("booleano em texto", "{\"interested\": \"true\"}", "interested", "INVALID_FORMAT"),
                invalid("escolha numérica", "{\"interested\": 1}", "interested", "INVALID_FORMAT"),
                invalid("par vindo do cliente", "{\"interested\": true, \"partnerAccountId\": \"x\"}",
                        "partnerAccountId", "UNKNOWN_FIELD"),
                Arguments.of(Named.of("JSON quebrado", "{\"interested\": "),
                        "[{\"code\": \"MALFORMED_BODY\"}]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"interested\": \"%s\"}", "{\"interested\": true, \"%s\": 1}", "{\"interested\": \"%s"})
    void rejectedValueIsNotEchoed(String bodyTemplate) throws Exception {
        var canary = "CANARY-" + UUID.randomUUID();

        var response = decide(bodyTemplate.formatted(canary))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"errors\"").doesNotContain(canary);
    }

    private static Arguments invalid(String name, String body, String field, String code) {
        return Arguments.of(Named.of(name, body), """
                [{"field": "%s", "code": "%s"}]""".formatted(field, code));
    }

    private ResultActions decide(String body) throws Exception {
        return mockMvc.perform(put(PATH).with(user("ana")).contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
