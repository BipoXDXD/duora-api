package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.chat.ChatFixtures.ENDS_AT;
import static bipo.tech.duoraapi.chat.ChatFixtures.STARTS_AT;
import static bipo.tech.duoraapi.chat.ChatFixtures.messagePath;
import static bipo.tech.duoraapi.chat.ChatFixtures.newKey;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Denúncia de uma mensagem do chat (docs/adr/0021, fatia 3): só quem está no par denuncia, só mensagem do outro,
 * e a denúncia guarda uma cópia da mensagem no trustsafety. Vale a cota de denúncias da conta (docs/adr/0015),
 * aqui de 3 por dia para o teste chegar ao limite. Os loggers ficam em DEBUG, o nível em que o Spring MVC registra
 * o corpo lido e escrito, para o canário do texto ser procurado onde ele vazaria.
 */
@SpringBootTest(properties = {
        "duora.chat.message-rate-limit.capacity=100000",
        "duora.trustsafety.report-rate-limit.capacity=3",
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.bipo.tech=DEBUG"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class ChatReportIT {

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final String HARASSMENT = "{\"reason\": \"HARASSMENT\", \"description\": \"na rodada 1\"}";

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
    void aPartnersMessageIsReportedWithACopyAsEvidence() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", "mensagem ofensiva");
        clock.advance(Duration.ofMinutes(5));

        var response = report(eventId, "ana", 1, HARASSMENT)
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        String id = JsonPath.read(response.getContentAsString(), "$.id");
        String expected = """
                {"id": "%s", "reportedAccountId": "%s", "reason": "HARASSMENT", "description": "na rodada 1",
                 "status": "OPEN", "createdAt": "%s"}
                """.formatted(id, accountOf("bruno"), STARTS_AT.plus(Duration.ofMinutes(5)));
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/reports/" + id);
        assertThat(response.getContentAsString()).doesNotContain("mensagem ofensiva");
        mockMvc.perform(get("/api/reports/" + id).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json(expected, JsonCompareMode.STRICT));
        assertThat(response.getContentAsString()).isEqualToIgnoringWhitespace(expected);
        assertThat(reportRows()).isEqualTo(accountOf("ana") + " -> " + accountOf("bruno") + " HARASSMENT");
        assertThat(evidenceOf(id)).isEqualTo(
                chatIdOf(eventId) + " " + eventId + " 1 1 mensagem ofensiva " + STARTS_AT);
    }

    /**
     * A própria mensagem é 400; a posição sem mensagem e o chat de outra pessoa são 404, iguais aos do chat. Nada
     * é gravado e a cota não é gasta: a leitura do chat já diz o mesmo, sem cota.
     */
    @Test
    void onlyThePartnersMessageCanBeReported() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "ana", "minha mensagem");
        send(eventId, "bruno", "segredo da dupla");
        paired("carla", "davi");

        report(eventId, "ana", 1, HARASSMENT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("a message of your own cannot be reported"));
        report(eventId, "ana", 3, HARASSMENT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("there is no message at this position of the chat"));
        String outsider = report(eventId, "carla", 2, HARASSMENT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("the caller has no chat in this round"))
                .andReturn().getResponse().getContentAsString();

        assertThat(outsider).doesNotContain("segredo", chatIdOf(eventId), accountOf("ana"), accountOf("bruno"));
        assertNothingWritten();
    }

    /** Quem bloqueou ainda relê a conversa para denunciar, até o expurgo (docs/adr/0021, pergunta 7). */
    @Test
    void whoBlockedCanStillReport() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", "ameaça");
        mockMvc.perform(post("/api/accounts/{id}:block", accountOf("bruno")).with(user("ana")))
                .andExpect(status().isNoContent());

        report(eventId, "ana", 1, HARASSMENT).andExpect(status().isCreated());

        assertThat(evidenceRows()).isOne();
    }

    /** Fechado para envio, o chat continua legível e denunciável até 24 h depois do fim do evento. */
    @Test
    void aClosedChatCanStillBeReportedUntilThePurge() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", "golpe");
        clock.setTo(ENDS_AT.plus(Duration.ofHours(24)));

        report(eventId, "ana", 1, "{\"reason\": \"SCAM_OR_SPAM\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").isEmpty());

        assertThat(evidenceRows()).isOne();
    }

    /** A cota é a mesma de POST /api/reports: as duas portas somam na conta de quem denuncia. */
    @Test
    void theReportQuotaIsSharedWithAccountReports() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", "primeira");
        send(eventId, "bruno", "segunda");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/reports").with(user("ana")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reportedAccountId\": \"%s\", \"reason\": \"HARASSMENT\"}"
                                    .formatted(accountOf("bruno"))))
                    .andExpect(status().isCreated());
        }
        report(eventId, "ana", 1, HARASSMENT).andExpect(status().isCreated());

        report(eventId, "ana", 2, HARASSMENT)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        assertThat(reportCount()).isEqualTo(3);
        assertThat(evidenceRows()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"reason\": \"OTHER\"}",
            "{\"reason\": \"OTHER\", \"description\": \"   \"}",
            "{\"reason\": \"UNKNOWN\"}",
            "{\"reason\": \"HARASSMENT\", \"description\": \"\\u0000\"}",
            "{\"reason\": \"HARASSMENT\", \"description\": \"DESCRIPTION_1001\"}",
            "{\"reason\": \"HARASSMENT\", \"reportedAccountId\": \"01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b\"}",
            "{\"reason\": \"HARASSMENT\", \"seq\": 2}",
            "not json"})
    void invalidReportIsRejectedWithoutWriting(String body) throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", "oi");

        report(eventId, "ana", 1, body.replace("DESCRIPTION_1001", "x".repeat(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertNothingWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "301", "abc", "1.5"})
    void anInvalidPositionIsABadRequest(String seq) throws Exception {
        String eventId = paired("ana", "bruno");

        mockMvc.perform(post(messagesPathOf(eventId) + "/" + seq + ":report").with(user("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content(HARASSMENT))
                .andExpect(status().isBadRequest());

        assertNothingWritten();
    }

    /** Ler a mensagem continua no GET sem ação; a ação só aceita POST. */
    @Test
    void theReportActionOnlyAcceptsPost() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", "oi");

        mockMvc.perform(get(messagePath(eventId, 1, 1) + ":report").with(user("ana")))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get(messagePath(eventId, 1, 1)).with(user("ana")))
                .andExpect(status().isOk());
    }

    @Test
    void aWebSessionWithoutCsrfTokenCannotReport() throws Exception {
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", "oi");

        mockMvc.perform(post(reportPath(eventId, 1)).with(webSession("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content(HARASSMENT))
                .andExpect(status().isForbidden());
        assertNothingWritten();

        mockMvc.perform(post(reportPath(eventId, 1)).with(webSession("ana")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(HARASSMENT))
                .andExpect(status().isCreated());
    }

    /** A mensagem copiada e o relato são dado sensível: nem a denúncia aceita nem a recusada os levam ao log. */
    @Test
    void theReportedMessageNeverReachesTheLog(CapturedOutput output) throws Exception {
        String canary = "CANARY-" + UUID.randomUUID();
        String eventId = paired("ana", "bruno");
        send(eventId, "bruno", canary + " texto");

        var accepted = report(eventId, "ana", 1,
                "{\"reason\": \"OTHER\", \"description\": \"%s relato\"}".formatted(canary))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        var refused = report(eventId, "ana", 1,
                "{\"reason\": \"OTHER\", \"description\": \"%s%s\"}".formatted(canary, "x".repeat(1000)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();

        assertThat(output.getAll()).doesNotContain(canary);
        assertThat(accepted.getContentAsString()).doesNotContain(canary + " texto");
        assertThat(refused.getContentAsString()).doesNotContain(canary);
    }

    private String paired(String... names) throws Exception {
        return ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, names);
    }

    private void send(String eventId, String name, String text) throws Exception {
        ChatFixtures.send(mockMvc, eventId, user(name), newKey(), text).andExpect(status().isCreated());
    }

    private ResultActions report(String eventId, String name, int seq, String body) throws Exception {
        return mockMvc.perform(post(reportPath(eventId, seq)).with(user(name))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String reportPath(String eventId, int seq) {
        return messagePath(eventId, 1, seq) + ":report";
    }

    private static String messagesPathOf(String eventId) {
        return ChatFixtures.messagesPath(eventId, 1);
    }

    private void assertNothingWritten() {
        assertThat(reportCount()).isZero();
        assertThat(evidenceRows()).isZero();
        assertThat(jdbcClient.sql("select count(*) from rate_limit_bucket where id like 'report:%'")
                .query(Long.class).single()).isZero();
    }

    private String reportRows() {
        return jdbcClient.sql("select reporter_account_id, reported_account_id, reason from report")
                .query((row, number) -> row.getString("reporter_account_id") + " -> "
                        + row.getString("reported_account_id") + " " + row.getString("reason"))
                .single();
    }

    private long reportCount() {
        return jdbcClient.sql("select count(*) from report").query(Long.class).single();
    }

    private long evidenceRows() {
        return jdbcClient.sql("select count(*) from report_message_evidence").query(Long.class).single();
    }

    private String evidenceOf(String reportId) {
        return jdbcClient.sql("""
                        select chat_id, event_id, round_number, seq, body, sent_at
                          from report_message_evidence where report_id = :id
                        """)
                .param("id", UUID.fromString(reportId))
                .query((row, number) -> row.getString("chat_id") + " " + row.getString("event_id") + " "
                        + row.getInt("round_number") + " " + row.getInt("seq") + " " + row.getString("body") + " "
                        + row.getObject("sent_at", OffsetDateTime.class).toInstant())
                .single();
    }

    private String chatIdOf(String eventId) {
        return jdbcClient.sql("select id::text from chat where event_id = cast(:id as uuid)")
                .param("id", eventId).query(String.class).single();
    }

    private String accountOf(String name) {
        return ChatFixtures.accountOf(jdbcClient, name);
    }

    private static RequestPostProcessor webSession(String name) {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", "oid-" + name));
    }

}
