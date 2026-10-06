package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

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
 * As recusas escritas fora do Spring MVC (segurança e rate limit) saem em problem+json com o
 * charset do JSON, UTF-8, ou sem charset; nunca no ISO-8859-1 padrão do Tomcat. HTTP de verdade,
 * porque é o Tomcat que acrescenta o charset.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ProblemCharsetIT {

    private static final int JOIN_CAPACITY = 10;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from rate_limit_bucket").update();
        jdbcClient.sql("delete from waitlist_entry").update();
    }

    @Test
    void webSessionRejectionIsUtf8() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/me")).GET());

        assertThat(response.statusCode()).isEqualTo(401);
        assertProblemJsonInUtf8(response);
    }

    @Test
    void bearerRejectionIsUtf8() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/me"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .GET());

        assertThat(response.statusCode()).isEqualTo(401);
        assertProblemJsonInUtf8(response);
    }

    @Test
    void rateLimitRejectionIsUtf8() throws Exception {
        HttpResponse<String> response = null;
        for (int i = 0; i <= JOIN_CAPACITY; i++) {
            response = send(HttpRequest.newBuilder(uri("/api/waitlist"))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"email": "user%d@example.com"}
                            """.formatted(i))));
        }

        assertThat(response.statusCode()).isEqualTo(429);
        assertProblemJsonInUtf8(response);
    }

    private static void assertProblemJsonInUtf8(HttpResponse<String> response) {
        MediaType contentType =
                MediaType.parseMediaType(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElseThrow());
        assertThat(contentType.equalsTypeAndSubtype(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        assertThat(contentType.getCharset()).isIn(null, StandardCharsets.UTF_8);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

}
