package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.chat.ChatFixtures;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Canário de vazamento (plano §7: nada de tokens, credenciais nem dados pessoais em log). Um valor
 * marcado vai em cada campo sensível que o cliente controla, e não pode reaparecer no log, na
 * resposta nem nos headers. Os loggers do Spring MVC e da própria API ficam em DEBUG, o nível que
 * alguém ligaria para investigar um incidente: é nele que o MVC registra o corpo lido. O perfil
 * passa pelo MockMvc com {@code jwt()}, que pula só a validação do token (testada em
 * {@code BearerTokenValidationIT}); filtros, MVC e o log são os de verdade.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.bipo.tech=DEBUG"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataLoggingIT {

    private static final String PROFILE_PATH = "/api/me/profile";
    private static final String FIRST_VERSION = "\"0\"";
    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    /** Data de nascimento marcada: improvável em qualquer outra linha de log. */
    private static final String CANARY_BIRTH_DATE = "1987-03-29";

    private static final String REPORTS_PATH = "/api/reports";
    private static final String REPORTED_OBJECT_ID = "oid-reported";

    private static final int CREATED = 201;
    private static final int ACCEPTED = 202;
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;
    private static final int OK = 200;

    /** Minúsculo, como o e-mail fica depois de normalizado: a busca não depende de maiúsculas. */
    private final String canary = "canary-" + UUID.randomUUID();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    /** Parado no início dos testes do chat, que precisam de um evento em andamento. */
    @Autowired
    private TestClock clock;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from waitlist_entry").update();
        jdbcClient.sql("delete from rate_limit_bucket").update();
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @Test
    void waitlistEmailNeverReachesTheLog(CapturedOutput output) throws Exception {
        var response = send(joinWaitlist(canary + "@example.com"));

        assertThat(response.statusCode()).isEqualTo(ACCEPTED);
        assertNoLeak(response, output);
    }

    @Test
    void rejectedEmailNeverReachesTheLog(CapturedOutput output) throws Exception {
        var response = send(joinWaitlist(canary + " not an email"));

        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST);
        assertNoLeak(response, output);
    }

    @Test
    void bearerTokenNeverReachesTheLog(CapturedOutput output) throws Exception {
        var response = send(request("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + canary));

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED);
        assertNoLeak(response, output);
    }

    @Test
    void basicCredentialsNeverReachTheLog(CapturedOutput output) throws Exception {
        String credentials = Base64.getEncoder().encodeToString(("ana:" + canary).getBytes(StandardCharsets.UTF_8));

        var response = send(request("/api/me").header(HttpHeaders.AUTHORIZATION, "Basic " + credentials));

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED);
        assertNoLeak(response, output);
    }

    @Test
    void cookiesNeverReachTheLog(CapturedOutput output) throws Exception {
        String session = Base64.getEncoder().encodeToString(canary.getBytes(StandardCharsets.UTF_8));

        var response = send(request("/api/me").header(HttpHeaders.COOKIE,
                WebSessionConfiguration.SESSION_COOKIE_NAME + "=" + session + "; XSRF-TOKEN=" + canary));

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED);
        assertNoLeak(response, output);
    }

    @Test
    void queryStringNeverReachesTheLog(CapturedOutput output) throws Exception {
        var response = send(request("/api/me?email=" + canary + "&token=" + canary));

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED);
        assertNoLeak(response, output);
    }

    /** Headers de rastreio inválidos não são ecoados nem registrados. */
    @Test
    void tracingHeadersNeverReachTheLog(CapturedOutput output) throws Exception {
        var response = send(request("/api/me")
                .header("X-Request-Id", canary)
                .header("traceparent", "00-" + canary + "-01")
                .header("baggage", "user=" + canary));

        assertThat(response.statusCode()).isEqualTo(UNAUTHORIZED);
        assertNoLeak(response, output);
    }

    /** O code do Entra troca-se por tokens; o callback nunca o registra, aceito ou recusado. */
    @Test
    void authorizationCodeNeverReachesTheLog(CapturedOutput output) throws Exception {
        var response = send(request("/login/oauth2/code/" + WebLoginConfiguration.REGISTRATION_ID
                + "?code=" + canary + "&state=" + canary));

        assertThat(response.statusCode()).isBetween(300, 499);
        assertNoLeak(response, output);
    }

    /** A resposta devolve o perfil ao dono; o log não leva nome, bio nem data de nascimento. */
    @Test
    void acceptedProfileNeverReachesTheLog(CapturedOutput output) throws Exception {
        var response = editProfile("""
                {"displayName": "%s", "bio": "%s", "birthDate": "%s", "region": "BR-SP"}
                """.formatted(canary, canary, CANARY_BIRTH_DATE));

        assertThat(response.getStatus()).isEqualTo(OK);
        assertThat(output.getAll()).doesNotContainIgnoringCase(canary).doesNotContain(CANARY_BIRTH_DATE);
    }

    /** Nome longo demais, bio com caractere invisível e data fora do formato: 400 sem ecoar o valor. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"displayName\": \"%s-0123456789\"}",
            "{\"bio\": \"%s\\u200b\"}",
            "{\"birthDate\": \"%s\"}"})
    void rejectedProfileValuesNeverReachTheLog(String bodyTemplate, CapturedOutput output) throws Exception {
        var response = editProfile(bodyTemplate.formatted(canary));

        assertThat(response.getStatus()).isEqualTo(BAD_REQUEST);
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase(canary);
        assertThat(response.getHeaderNames()).allSatisfy(name ->
                assertThat(String.join(",", response.getHeaders(name))).doesNotContainIgnoringCase(canary));
        assertThat(output.getAll()).doesNotContainIgnoringCase(canary);
    }

    /** A resposta devolve a denúncia a quem a fez; o log não leva o relato (docs/adr/0015). */
    @Test
    void acceptedReportDescriptionNeverReachesTheLog(CapturedOutput output) throws Exception {
        var response = fileReport("""
                {"reportedAccountId": "%s", "reason": "OTHER", "description": "%s"}
                """.formatted(reportedAccountId(), canary));

        assertThat(response.getStatus()).isEqualTo(CREATED);
        assertThat(output.getAll()).doesNotContainIgnoringCase(canary);
    }

    /** Relato longo demais ou com caractere invisível: 400 sem ecoar o texto. */
    @ParameterizedTest
    @ValueSource(strings = {"%s%s", "%s\\u200b"})
    void rejectedReportDescriptionNeverReachesTheLog(String descriptionTemplate, CapturedOutput output) throws Exception {
        var description = descriptionTemplate.formatted(canary, "x".repeat(1000));

        var response = fileReport("""
                {"reportedAccountId": "%s", "reason": "OTHER", "description": "%s"}
                """.formatted(reportedAccountId(), description));

        assertThat(response.getStatus()).isEqualTo(BAD_REQUEST);
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase(canary);
        assertThat(response.getHeaderNames()).allSatisfy(name ->
                assertThat(String.join(",", response.getHeaders(name))).doesNotContainIgnoringCase(canary));
        assertThat(output.getAll()).doesNotContainIgnoringCase(canary);
    }

    /**
     * A conversa só volta para as duas pessoas do par; o log não leva o texto, nem no envio nem na leitura
     * (docs/adr/0021).
     */
    @Test
    void chatMessageNeverReachesTheLog(CapturedOutput output) throws Exception {
        String eventId = ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, "ana", "bruno");

        var sent = ChatFixtures.send(mockMvc, eventId, EventFixtures.user("ana"), ChatFixtures.newKey(), canary)
                .andReturn().getResponse();
        var read = mockMvc.perform(get(ChatFixtures.messagesPath(eventId, 1)).with(EventFixtures.user("bruno")))
                .andReturn().getResponse();

        assertThat(sent.getStatus()).isEqualTo(CREATED);
        assertThat(read.getContentAsString()).containsIgnoringCase(canary);
        assertThat(output.getAll()).doesNotContainIgnoringCase(canary);
    }

    /** Texto longo demais ou com caractere invisível: 400 sem ecoar o texto. */
    @ParameterizedTest
    @ValueSource(strings = {"%s%s", "%s​"})
    void rejectedChatMessageNeverReachesTheLog(String textTemplate, CapturedOutput output) throws Exception {
        String eventId = ChatFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, "ana", "bruno");

        var response = ChatFixtures.send(mockMvc, eventId, EventFixtures.user("ana"), ChatFixtures.newKey(),
                textTemplate.formatted(canary, "x".repeat(500))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(BAD_REQUEST);
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase(canary);
        assertThat(response.getHeaderNames()).allSatisfy(name ->
                assertThat(String.join(",", response.getHeaders(name))).doesNotContainIgnoringCase(canary));
        assertThat(output.getAll()).doesNotContainIgnoringCase(canary);
    }

    private MockHttpServletResponse fileReport(String body) throws Exception {
        return mockMvc.perform(post(REPORTS_PATH)
                        .with(jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-" + UUID.randomUUID())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    /** Abre a conta denunciada pelo primeiro acesso, como em produção. */
    private UUID reportedAccountId() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(token -> token.issuer(ISSUER).claim("oid", REPORTED_OBJECT_ID))));
        return jdbcClient.sql("select id from account where subject = :subject")
                .param("subject", REPORTED_OBJECT_ID)
                .query(UUID.class).single();
    }

    private MockHttpServletResponse editProfile(String body) throws Exception {
        return mockMvc.perform(patch(PROFILE_PATH)
                        .with(jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-" + UUID.randomUUID())))
                        .header(HttpHeaders.IF_MATCH, FIRST_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private void assertNoLeak(HttpResponse<String> response, CapturedOutput output) {
        assertThat(response.body()).doesNotContainIgnoringCase(canary);
        assertThat(response.headers().map()).allSatisfy((name, values) ->
                assertThat(values).noneSatisfy(value -> assertThat(value).containsIgnoringCase(canary)));
        assertThat(output.getAll()).doesNotContainIgnoringCase(canary);
    }

    private HttpRequest.Builder joinWaitlist(String email) {
        return request("/api/waitlist")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "%s"}
                        """.formatted(email)));
    }

    private HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

}
