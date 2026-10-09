package bipo.tech.duoraapi.trustsafety;

import static bipo.tech.duoraapi.ProblemJson.strictIgnoringDetail;
import static bipo.tech.duoraapi.RateLimitTestSupport.clearBuckets;
import static bipo.tech.duoraapi.TestIdentities.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
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

import bipo.tech.duoraapi.AccountFixtures;
import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * O 400 do corpo do {@code POST /api/reports} diz qual campo falhou e por quê em {@code errors}
 * (docs/adr/0018), sem repetir o valor recebido. O jwt() pula a validação do token, o que basta aqui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReportFieldErrorsIT {

    private static final String REPORTS_PATH = "/api/reports";
    private static final String REPORTER = "oid-reporter-field-errors";
    private static final String REPORTED = "oid-reported-field-errors";
    /** Trocado pelo id da conta denunciada, que só existe depois do primeiro acesso dela. */
    private static final String REPORTED_ID = "{reportedId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
        clearBuckets(jdbcClient);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String errors) throws Exception {
        file(body.replace(REPORTED_ID, accountIdOf(REPORTED)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "instance": "%s", "errors": %s}
                        """.formatted(REPORTS_PATH, errors), strictIgnoringDetail()));

        assertThat(reportRows()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of(Named.of("sem conta nem motivo", "{}"), """
                        [{"field": "reason", "code": "REQUIRED"}, {"field": "reportedAccountId", "code": "REQUIRED"}]"""),
                invalid("sem motivo", "{\"reportedAccountId\": \"" + REPORTED_ID + "\"}", "reason", "REQUIRED"),
                invalid("id que não é UUID", "{\"reportedAccountId\": \"bruno\", \"reason\": \"HARASSMENT\"}",
                        "reportedAccountId", "INVALID_FORMAT"),
                invalid("motivo fora da lista", "{\"reportedAccountId\": \"" + REPORTED_ID + "\", \"reason\": \"INSULT\"}",
                        "reason", "UNSUPPORTED_VALUE"),
                invalid("OTHER sem descrição", "{\"reportedAccountId\": \"" + REPORTED_ID + "\", \"reason\": \"OTHER\"}",
                        "description", "REQUIRED"),
                invalid("descrição com 1001 caracteres", withDescription("a".repeat(1001)), "description", "TOO_LONG"),
                invalid("descrição com NUL", withDescription("oi\\u0000"), "description", "FORBIDDEN_CHARACTER"),
                invalid("estado vindo do cliente", "{\"reportedAccountId\": \"" + REPORTED_ID + "\", \"reason\": \"HARASSMENT\", \"status\": \"CLOSED\"}",
                        "status", "UNKNOWN_FIELD"),
                Arguments.of(Named.of("JSON quebrado", "{\"reason\": "),
                        "[{\"code\": \"MALFORMED_BODY\"}]"));
    }

    @Test
    void reportingYourselfNamesTheReportedAccount() throws Exception {
        file("{\"reportedAccountId\": \"%s\", \"reason\": \"HARASSMENT\"}".formatted(accountIdOf(REPORTER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "instance": "/api/reports",
                         "errors": [{"field": "reportedAccountId", "code": "SELF_REFERENCE"}]}
                        """, strictIgnoringDetail()));
    }

    /** O relato é dado sensível: o valor recusado não volta, nem a chave desconhecida fora do formato de nome. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"reportedAccountId\": \"{reportedId}\", \"reason\": \"OTHER\", \"description\": \"%s\\u0000\"}",
            "{\"reportedAccountId\": \"%s\", \"reason\": \"HARASSMENT\"}",
            "{\"reportedAccountId\": \"{reportedId}\", \"reason\": \"%s\"}",
            "{\"reportedAccountId\": \"{reportedId}\", \"reason\": \"HARASSMENT\", \"%s\": 1}",
            "{\"reportedAccountId\": \"{reportedId}\", \"reason\": \"%s"})
    void rejectedValueIsNotEchoed(String bodyTemplate) throws Exception {
        var canary = "CANARY-" + UUID.randomUUID();

        var response = file(bodyTemplate.formatted(canary).replace(REPORTED_ID, accountIdOf(REPORTED)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"errors\"").doesNotContain(canary);
    }

    private static Arguments invalid(String name, String body, String field, String code) {
        return Arguments.of(Named.of(name, body), """
                [{"field": "%s", "code": "%s"}]""".formatted(field, code));
    }

    private static String withDescription(String description) {
        return "{\"reportedAccountId\": \"" + REPORTED_ID + "\", \"reason\": \"OTHER\", \"description\": \""
                + description + "\"}";
    }

    private ResultActions file(String body) throws Exception {
        return mockMvc.perform(post(REPORTS_PATH).with(bearer(REPORTER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** O primeiro acesso cria a conta; o id é o que o banco gerou. */
    private String accountIdOf(String objectId) throws Exception {
        return AccountFixtures.openAccount(mockMvc, jdbcClient, objectId);
    }

    private long reportRows() {
        return jdbcClient.sql("select count(*) from report").query(Long.class).single();
    }

}
