package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Cookie de sessão com um id que a API nunca emitiria vale como sessão ausente. Antes, um id com NUL
 * chegava à consulta do Spring Session no PostgreSQL, que o recusa, e qualquer rota respondia 500 em
 * HTML; o Schemathesis achou o caso (docs/adr/0012). HTTP de verdade, para passar pelo Tomcat e pelo
 * encaminhamento a /error como em produção.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MalformedSessionCookieIT {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "not-a-session-id", "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b\u0000"})
    void malformedSessionIdIsTreatedAsNoSession(String sessionId) throws Exception {
        String cookieValue = Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));

        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
                        .header(HttpHeaders.COOKIE, WebSessionConfiguration.SESSION_COOKIE_NAME + "=" + cookieValue)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).map(MediaType::parseMediaType))
                .hasValueSatisfying(type -> assertThat(type.equalsTypeAndSubtype(MediaType.APPLICATION_PROBLEM_JSON))
                        .isTrue());
    }

}
