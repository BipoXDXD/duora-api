package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Canário de vazamento (plano §7: nada de tokens, credenciais nem dados pessoais em log). Um valor
 * marcado vai em cada campo sensível que o cliente controla, e não pode reaparecer no log, na
 * resposta nem nos headers. Os loggers do Spring MVC e da própria API ficam em DEBUG, o nível que
 * alguém ligaria para investigar um incidente: é nele que o MVC registra o corpo lido.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.bipo.tech=DEBUG"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SensitiveDataLoggingIT {

    private static final int ACCEPTED = 202;
    private static final int BAD_REQUEST = 400;
    private static final int UNAUTHORIZED = 401;

    /** Minúsculo, como o e-mail fica depois de normalizado: a busca não depende de maiúsculas. */
    private final String canary = "canary-" + UUID.randomUUID();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from waitlist_entry").update();
        jdbcClient.sql("delete from rate_limit_bucket").update();
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
