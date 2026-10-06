package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
class UnexpectedErrorIT {

    private static final String HIDDEN_TABLE = "waitlist_entry_unavailable";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanRateLimit() {
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    @AfterEach
    void restoreWaitlistTable() {
        jdbcClient.sql("alter table if exists " + HIDDEN_TABLE + " rename to waitlist_entry").update();
    }

    @Test
    void unexpectedFailureOnPublicRouteAnswersServerErrorAsProblemDetail() throws Exception {
        jdbcClient.sql("alter table waitlist_entry rename to " + HIDDEN_TABLE).update();

        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/waitlist"))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"email": "ana@example.com"}
                                """))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.body())
                .contains("\"status\":500")
                .doesNotContain("Exception", "SQL", "at ", "waitlist_entry", "bipo.tech", "trace");
    }

}
