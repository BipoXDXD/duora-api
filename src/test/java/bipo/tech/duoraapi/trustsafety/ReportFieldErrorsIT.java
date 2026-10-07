package bipo.tech.duoraapi.trustsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
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
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String detail, String errors) throws Exception {
        file(body.replace(REPORTED_ID, accountIdOf(REPORTED)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "%s", "instance": "%s", "errors": %s}
                        """.formatted(detail, REPORTS_PATH, errors), JsonCompareMode.STRICT));

        assertThat(reportRows()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of(Named.of("sem conta nem motivo", "{}"), "Invalid request content.", """
                        [{"field": "reason", "code": "REQUIRED"}, {"field": "reportedAccountId", "code": "REQUIRED"}]"""),
                invalid("sem motivo", "{\"reportedAccountId\": \"" + REPORTED_ID + "\"}",
                        "Invalid request content.", "reason", "REQUIRED"),
                invalid("id que não é UUID", "{\"reportedAccountId\": \"bruno\", \"reason\": \"HARASSMENT\"}",
                        "Failed to read request", "reportedAccountId", "INVALID_FORMAT"),
                invalid("motivo fora da lista", "{\"reportedAccountId\": \"" + REPORTED_ID + "\", \"reason\": \"INSULT\"}",
                        "Failed to read request", "reason", "UNSUPPORTED_VALUE"),
                invalid("OTHER sem descrição", "{\"reportedAccountId\": \"" + REPORTED_ID + "\", \"reason\": \"OTHER\"}",
                        "description is required when the reason is OTHER", "description", "REQUIRED"),
                invalid("descrição com 1001 caracteres", withDescription("a".repeat(1001)),
                        "description must have at most 1000 characters", "description", "TOO_LONG"),
                invalid("descrição com NUL", withDescription("oi\\u0000"),
                        "description contains a forbidden character", "description", "FORBIDDEN_CHARACTER"),
                invalid("estado vindo do cliente", "{\"reportedAccountId\": \"" + REPORTED_ID
                                + "\", \"reason\": \"HARASSMENT\", \"status\": \"CLOSED\"}",
                        "Failed to read request", "status", "UNKNOWN_FIELD"),
                Arguments.of(Named.of("JSON quebrado", "{\"reason\": "), "Failed to read request",
                        "[{\"code\": \"MALFORMED_BODY\"}]"));
    }

    @Test
    void reportingYourselfNamesTheReportedAccount() throws Exception {
        file("{\"reportedAccountId\": \"%s\", \"reason\": \"HARASSMENT\"}".formatted(accountIdOf(REPORTER)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "an account cannot report itself",
                         "instance": "/api/reports", "errors": [{"field": "reportedAccountId", "code": "SELF_REFERENCE"}]}
                        """, JsonCompareMode.STRICT));
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

    private static Arguments invalid(String name, String body, String detail, String field, String code) {
        return Arguments.of(Named.of(name, body), detail, """
                [{"field": "%s", "code": "%s"}]""".formatted(field, code));
    }

    private static String withDescription(String description) {
        return "{\"reportedAccountId\": \"" + REPORTED_ID + "\", \"reason\": \"OTHER\", \"description\": \""
                + description + "\"}";
    }

    private ResultActions file(String body) throws Exception {
        return mockMvc.perform(post(REPORTS_PATH).with(user(REPORTER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** O primeiro acesso cria a conta; o id é o que o banco gerou. */
    private String accountIdOf(String objectId) throws Exception {
        mockMvc.perform(get("/api/me").with(user(objectId))).andExpect(status().isOk());
        return jdbcClient.sql("select id from account where subject = :subject")
                .param("subject", objectId)
                .query(UUID.class).single().toString();
    }

    private long reportRows() {
        return jdbcClient.sql("select count(*) from report").query(Long.class).single();
    }

    private static RequestPostProcessor user(String objectId) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", objectId));
    }

}
