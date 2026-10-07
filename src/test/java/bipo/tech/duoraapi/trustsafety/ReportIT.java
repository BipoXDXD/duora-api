package bipo.tech.duoraapi.trustsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Denúncia entre contas (docs/adr/0015): motivo de lista fechada, descrição limitada, estado inicial
 * OPEN, cota por conta e ninguém lê denúncia alheia. O jwt() e o oidcLogin() pulam a validação do
 * token, o que basta aqui: ela está em BearerTokenValidationIT e WebLoginIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ReportIT {

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final String REPORTS_PATH = "/api/reports";
    private static final String UNKNOWN_ID = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";
    /** duora.trustsafety.report-rate-limit: 10 por dia, uma ficha de volta a cada 2,4 h. */
    private static final int DAILY_QUOTA = 10;
    private static final String SECONDS_TO_NEXT_REPORT = "8640";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    @Test
    void filingAReportRecordsItOpenForModeration() throws Exception {
        var ana = accountIdOf("oid-ana");
        var bruno = accountIdOf("oid-bruno");

        var response = file(ana(), """
                {"reportedAccountId": "%s", "reason": "HARASSMENT", "description": "Mensagens ofensivas"}
                """.formatted(bruno))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        String id = JsonPath.read(response.getContentAsString(), "$.id");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo("http://localhost/api/reports/" + id);
        assertThat(response.getContentAsString()).isEqualToIgnoringWhitespace("""
                {"id":"%s","reportedAccountId":"%s","reason":"HARASSMENT","description":"Mensagens ofensivas",
                "status":"OPEN","createdAt":"%s"}
                """.formatted(id, bruno, createdAtOf(id)));
        assertThat(reportRows()).containsExactly(ana + " -> " + bruno + " HARASSMENT OPEN");
    }

    @Test
    void listedReasonNeedsNoDescription() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        file(ana(), """
                {"reportedAccountId": "%s", "reason": "FAKE_PROFILE"}
                """.formatted(bruno))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").isEmpty());

        assertThat(reportRows()).hasSize(1);
    }

    /** Descrição em branco, com motivo da lista, é descrição ausente, e não erro. */
    @Test
    void blankDescriptionWithAListedReasonCountsAsNone() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        file(ana(), """
                {"reportedAccountId": "%s", "reason": "HARASSMENT", "description": "   "}
                """.formatted(bruno))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").isEmpty());
    }

    @Test
    void reporterReadsTheirOwnReport() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        var created = file(ana(), harassmentOf(bruno)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(get(REPORTS_PATH + "/{id}", id).with(ana()))
                .andExpect(status().isOk())
                .andExpect(content().json(created, JsonCompareMode.STRICT));
    }

    /** A denúncia de outra pessoa responde igual a um id que não existe, sem nada dela no corpo. */
    @Test
    void nobodyReadsSomeoneElsesReport() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        var created = file(ana(), harassmentOf(bruno)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        var othersReport = mockMvc.perform(get(REPORTS_PATH + "/{id}", id).with(bruno()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();
        var unknownReport = mockMvc.perform(get(REPORTS_PATH + "/{id}", UNKNOWN_ID).with(bruno()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(othersReport.replace(id, UNKNOWN_ID)).isEqualTo(unknownReport);
        assertThat(othersReport).doesNotContain("Mensagens", "HARASSMENT", bruno);
    }

    @Test
    void reportingYourselfIsRejectedWithoutWriting() throws Exception {
        var ana = accountIdOf("oid-ana");

        file(ana(), harassmentOf(ana))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "an account cannot report itself",
                         "instance": "/api/reports", "errors": [{"field": "reportedAccountId", "code": "SELF_REFERENCE"}]}
                        """, JsonCompareMode.STRICT));

        assertThat(reportRows()).isEmpty();
    }

    @Test
    void reportingAnUnknownAccountIsNotFoundWithoutWriting() throws Exception {
        file(ana(), harassmentOf(UNKNOWN_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"title": "Not Found", "status": 404, "detail": "account not found", "instance": "/api/reports"}
                        """, JsonCompareMode.STRICT));

        assertThat(reportRows()).isEmpty();
    }

    /** Denunciar quem bloqueou você funciona como qualquer denúncia: a resposta não revela o bloqueio. */
    @Test
    void reportingSomeoneWhoBlockedYouLooksLikeAnyOtherReport() throws Exception {
        var ana = accountIdOf("oid-ana");
        var bruno = accountIdOf("oid-bruno");
        mockMvc.perform(post("/api/accounts/{id}:block", ana).with(bruno())).andExpect(status().isNoContent());

        file(ana(), harassmentOf(bruno)).andExpect(status().isCreated());

        assertThat(reportRows()).hasSize(1);
    }

    @Test
    void descriptionOfAThousandCharactersIsAccepted() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        file(ana(), """
                {"reportedAccountId": "%s", "reason": "OTHER", "description": "%s"}
                """.formatted(bruno, "a".repeat(1000)))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputIsRejectedWithoutWriting(String bodyTemplate) throws Exception {
        var bruno = accountIdOf("oid-bruno");

        file(ana(), bodyTemplate.replace("{bruno}", bruno))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(reportRows()).isEmpty();
    }

    static Stream<Named<String>> invalidBodies() {
        return Stream.of(
                Named.of("sem conta denunciada", "{\"reason\": \"HARASSMENT\"}"),
                Named.of("conta denunciada nula", "{\"reportedAccountId\": null, \"reason\": \"HARASSMENT\"}"),
                Named.of("conta denunciada que não é UUID", "{\"reportedAccountId\": \"ana\", \"reason\": \"HARASSMENT\"}"),
                Named.of("sem motivo", "{\"reportedAccountId\": \"{bruno}\"}"),
                Named.of("motivo fora da lista", "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"BORING\"}"),
                Named.of("motivo em minúsculas", "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"harassment\"}"),
                Named.of("motivo com injeção de SQL",
                        "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"HARASSMENT' OR '1'='1\"}"),
                Named.of("OTHER sem descrição", "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"OTHER\"}"),
                Named.of("OTHER com descrição em branco",
                        "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"OTHER\", \"description\": \"  \"}"),
                Named.of("descrição com 1001 caracteres", "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"OTHER\", "
                        + "\"description\": \"" + "a".repeat(1001) + "\"}"),
                Named.of("descrição com NUL",
                        "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"OTHER\", \"description\": \"oi\\u0000\"}"),
                Named.of("descrição com controle de direção",
                        "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"OTHER\", \"description\": \"oi\\u202e\"}"),
                Named.of("descrição como objeto",
                        "{\"reportedAccountId\": \"{bruno}\", \"reason\": \"OTHER\", \"description\": {\"text\": \"oi\"}}"),
                Named.of("JSON quebrado", "{\"reportedAccountId\": "),
                Named.of("lista no lugar do objeto", "[]"),
                Named.of("null no lugar do objeto", "null"));
    }

    /** Estado, autoria e id são do servidor (mass assignment). */
    @ParameterizedTest
    @ValueSource(strings = {"\"status\": \"RESOLVED\"", "\"reporterAccountId\": \"" + UNKNOWN_ID + "\"",
            "\"id\": \"" + UNKNOWN_ID + "\"", "\"createdAt\": \"2020-01-01T00:00:00Z\"", "\"block\": true"})
    void unknownFieldIsRejectedWithoutWriting(String extraField) throws Exception {
        var bruno = accountIdOf("oid-bruno");

        file(ana(), """
                {"reportedAccountId": "%s", "reason": "HARASSMENT", %s}
                """.formatted(bruno, extraField))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(reportRows()).isEmpty();
    }

    /** Contra denúncias em massa: a cota é por conta, compartilhada entre réplicas (docs/adr/0006). */
    @Test
    void reportingAboveTheDailyQuotaIsRejectedWithRetryAfterAndWithoutWriting() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        for (int i = 0; i < DAILY_QUOTA; i++) {
            file(ana(), harassmentOf(bruno)).andExpect(status().isCreated());
        }

        file(ana(), harassmentOf(bruno))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, SECONDS_TO_NEXT_REPORT))
                .andExpect(content().json("""
                        {"title": "Too Many Requests", "status": 429, "detail": "report quota exceeded; try again later",
                         "instance": "/api/reports"}
                        """, JsonCompareMode.STRICT));

        assertThat(reportRows()).hasSize(DAILY_QUOTA);
    }

    /** Só denúncia válida gasta a cota: quem erra o formulário não fica sem denunciar depois. */
    @Test
    void rejectedReportsDoNotSpendTheQuota() throws Exception {
        var ana = accountIdOf("oid-ana");
        var bruno = accountIdOf("oid-bruno");
        for (int i = 0; i < DAILY_QUOTA; i++) {
            file(ana(), harassmentOf(ana)).andExpect(status().isBadRequest());
        }

        file(ana(), harassmentOf(bruno)).andExpect(status().isCreated());
    }

    /** Contas inexistentes gastam a cota, o que limita quem tenta adivinhar ids. */
    @Test
    void reportsOfUnknownAccountsSpendTheQuota() throws Exception {
        accountIdOf("oid-ana");
        for (int i = 0; i < DAILY_QUOTA; i++) {
            file(ana(), harassmentOf(UNKNOWN_ID)).andExpect(status().isNotFound());
        }

        file(ana(), harassmentOf(UNKNOWN_ID)).andExpect(status().isTooManyRequests());
    }

    /** Falha fechada (docs/adr/0006): sem contar a cota, a denúncia é recusada, e nada é gravado. */
    @Test
    void reportIsRefusedWithoutWritingWhenTheQuotaCannotBeCounted() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        jdbcClient.sql("alter table rate_limit_bucket rename to rate_limit_bucket_unavailable").update();
        try {
            file(ana(), harassmentOf(bruno))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().json("""
                            {"title": "Service Unavailable", "status": 503, "instance": "/api/reports"}
                            """, JsonCompareMode.STRICT));
        } finally {
            jdbcClient.sql("alter table rate_limit_bucket_unavailable rename to rate_limit_bucket").update();
        }

        assertThat(reportRows()).isEmpty();
    }

    @Test
    void quotaIsCountedForEachAccountSeparately() throws Exception {
        var carla = accountIdOf("oid-carla");
        for (int i = 0; i < DAILY_QUOTA; i++) {
            file(ana(), harassmentOf(carla)).andExpect(status().isCreated());
        }

        file(bruno(), harassmentOf(carla)).andExpect(status().isCreated());
    }

    /** O relato pode citar terceiros e o próprio denunciante: nunca vai para o log, aceito ou recusado. */
    @ParameterizedTest
    @ValueSource(strings = {"%s", "%s\\u0000", "%s%s"})
    void reportTextNeverReachesTheLog(String descriptionTemplate, CapturedOutput output) throws Exception {
        var bruno = accountIdOf("oid-bruno");
        var canary = "CANARY-" + UUID.randomUUID();
        var description = descriptionTemplate.formatted(canary, "x".repeat(1000));

        file(ana(), """
                {"reportedAccountId": "%s", "reason": "OTHER", "description": "%s"}
                """.formatted(bruno, description));

        assertThat(output.getAll()).doesNotContain(canary);
    }

    @Test
    void rejectedDescriptionIsNotEchoedInTheResponse() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        var canary = "CANARY-" + UUID.randomUUID();

        var response = file(ana(), """
                {"reportedAccountId": "%s", "reason": "OTHER", "description": "%s%s"}
                """.formatted(bruno, canary, "x".repeat(1000)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain(canary);
        assertThat(response.getHeaderNames()).allSatisfy(
                name -> assertThat(String.join(",", response.getHeaders(name))).doesNotContain(canary));
    }

    @Test
    void webSessionReportWithoutCsrfTokenIsRejectedWithoutWriting() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        mockMvc.perform(post(REPORTS_PATH).with(anaWebSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(harassmentOf(bruno)))
                .andExpect(status().isForbidden());

        assertThat(reportRows()).isEmpty();
    }

    @Test
    void anonymousCannotReportNorRead() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        mockMvc.perform(post(REPORTS_PATH).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(harassmentOf(bruno)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(REPORTS_PATH + "/{id}", UNKNOWN_ID)).andExpect(status().isUnauthorized());

        assertThat(reportRows()).isEmpty();
    }

    private ResultActions file(RequestPostProcessor user, String body) throws Exception {
        return mockMvc.perform(post(REPORTS_PATH).with(user)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String harassmentOf(String accountId) {
        return """
                {"reportedAccountId": "%s", "reason": "HARASSMENT", "description": "Mensagens ofensivas"}
                """.formatted(accountId);
    }

    /** Abre a conta pelo primeiro acesso, como acontece em produção, e devolve o id dela. */
    private String accountIdOf(String objectId) throws Exception {
        mockMvc.perform(get("/api/me").with(user(objectId))).andExpect(status().isOk());
        return jdbcClient.sql("select id from account where subject = :subject")
                .param("subject", objectId)
                .query(UUID.class).single().toString();
    }

    private List<String> reportRows() {
        return jdbcClient.sql("select reporter_account_id, reported_account_id, reason, status from report")
                .query((row, number) -> row.getString("reporter_account_id") + " -> "
                        + row.getString("reported_account_id") + " " + row.getString("reason") + " "
                        + row.getString("status"))
                .list();
    }

    private String createdAtOf(String reportId) {
        return jdbcClient.sql("select created_at from report where id = :id")
                .param("id", UUID.fromString(reportId))
                .query(OffsetDateTime.class).single().toInstant().toString();
    }

    private static RequestPostProcessor ana() {
        return user("oid-ana");
    }

    private static RequestPostProcessor bruno() {
        return user("oid-bruno");
    }

    private static RequestPostProcessor user(String objectId) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", objectId));
    }

    private static RequestPostProcessor anaWebSession() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", "oid-ana"));
    }

}
