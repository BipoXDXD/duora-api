package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.RateLimitTestSupport.clearBuckets;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
 * Exceção não tratada, com HTTP de verdade: o Tomcat a encaminha para /error, o que o MockMvc não
 * faz. Esse encaminhamento passa de novo pela autorização, e se ela o recusasse o cliente veria 401.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UnexpectedErrorIT {

    private static final String REQUEST_ID = "X-Request-Id";

    private static final String HIDDEN_TABLE = "waitlist_entry_unavailable";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanRateLimit() {
        clearBuckets(jdbcClient);
    }

    @AfterEach
    void restoreWaitlistTable() {
        jdbcClient.sql("alter table if exists " + HIDDEN_TABLE + " rename to waitlist_entry").update();
    }

    @Test
    void unexpectedFailureOnPublicRouteAnswersServerErrorAsProblemDetail() throws Exception {
        var response = joinWhileTableIsMissing("ana@example.com");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.body())
                .contains("\"status\":500")
                .doesNotContain("Exception", "SQL", "at ", "waitlist_entry", "bipo.tech", "trace");
    }

    /**
     * O cliente recebe só o id; o stack trace fica no log, numa linha com o mesmo id. O e-mail
     * enviado (canário) não aparece em nenhum dos dois.
     */
    @Test
    void unexpectedFailureIsLoggedWithStackTraceUnderTheRequestId(CapturedOutput output) throws Exception {
        String canary = "canary-" + UUID.randomUUID();

        var response = joinWhileTableIsMissing(canary + "@example.com");

        String requestId = response.headers().firstValue(REQUEST_ID).orElseThrow();
        assertThat(response.body()).contains("\"requestId\":\"" + requestId + "\"");
        assertThat(output.getOut().lines().filter(line -> line.contains(requestId)))
                .anySatisfy(line -> assertThat(line)
                        .contains("\"level\":\"ERROR\"", "\"stack_trace\":", "\\tat ", "PSQLException"));
        assertThat(response.body()).doesNotContain(canary);
        assertThat(output.getAll()).doesNotContain(canary);
    }

    private HttpResponse<String> joinWhileTableIsMissing(String email) throws Exception {
        jdbcClient.sql("alter table waitlist_entry rename to " + HIDDEN_TABLE).update();
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/waitlist"))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"email": "%s"}
                                """.formatted(email)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

}
