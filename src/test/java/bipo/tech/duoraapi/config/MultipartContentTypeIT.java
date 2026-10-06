package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
 * A API não recebe multipart. Um {@code Content-Type: multipart/form-data} malformado (sem boundary)
 * respondia 500, porque o Spring tentava ler as partes antes de chegar ao handler; o Schemathesis
 * achou o caso (docs/adr/0011). HTTP de verdade: o MockMvc não lê partes.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MultipartContentTypeIT {

    private static final String MULTIPART_WITHOUT_BOUNDARY = "multipart/form-data";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbcClient;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void cleanRateLimit() {
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    @Test
    void postWithMalformedMultipartIsUnsupportedMediaType() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/api/waitlist"))
                .header(HttpHeaders.CONTENT_TYPE, MULTIPART_WITHOUT_BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "ana@example.com"}
                        """)));

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    @Test
    void getWithMalformedMultipartIgnoresTheContentType() throws Exception {
        var response = send(HttpRequest.newBuilder(uri("/actuator/health"))
                .header(HttpHeaders.CONTENT_TYPE, MULTIPART_WITHOUT_BOUNDARY)
                .GET());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

}
