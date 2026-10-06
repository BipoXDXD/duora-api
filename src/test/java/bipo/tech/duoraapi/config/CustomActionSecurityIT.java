package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.testfixtures.routing.ThingActionsController;

/**
 * Uma regra de autorização sobre {@code POST /x/{id}:verbo} (docs/adr/0005) não pode ser contornada
 * pela grafia do ":". HTTP de verdade, para passar pelo Tomcat, pelo firewall e pela cadeia de
 * filtros como em produção; a regra é de teste porque a API ainda não tem ação protegida.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ThingActionsController.class,
        CustomActionSecurityIT.CancelIsDenied.class})
class CustomActionSecurityIT {

    private static final String ID = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Controle: a cadeia de teste deixa passar o resto, e o controller roteia a ação. */
    @Test
    void allowedActionReachesItsHandler() throws Exception {
        var response = post("/things/" + ID + ":accept");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("accept " + ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {":cancel", "%3Acancel", "%3acancel"})
    void deniedActionIsDeniedInEverySpellingOfTheColon(String action) throws Exception {
        var response = post("/things/" + ID + action);

        assertThat(response.statusCode()).isIn(401, 403);
        assertThat(response.body()).doesNotContain("cancel " + ID);
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CancelIsDenied {

        @Bean
        @Order(0)
        SecurityFilterChain thingActionsFilterChain(HttpSecurity http) {
            return http
                    .securityMatcher("/things/**")
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.POST, "/things/{id}:cancel").denyAll()
                            .anyRequest().permitAll())
                    // Rotas de teste sem sessão: o CSRF só atrapalharia o POST anônimo.
                    .csrf(csrf -> csrf.disable())
                    .build();
        }

    }

}
