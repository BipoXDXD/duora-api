package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Atrás do ingress, o rate limit identifica o cliente pelo {@code X-Forwarded-For}, mas só quando
 * quem abre a conexão é um proxy confiável. HTTP de verdade, porque quem lê o header é a
 * {@code RemoteIpValve} do Tomcat, que o MockMvc não executa. O teste conecta por 127.0.0.1, e cada
 * cliente pode fazer uma única inscrição.
 */
class ForwardedClientAddressIT {

    private static final String CLIENT_A = "198.51.100.21";
    private static final String CLIENT_B = "198.51.100.22";
    private static final int ACCEPTED = 202;
    private static final int TOO_MANY_REQUESTS = 429;

    private final HttpClient http = HttpClient.newHttpClient();

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
            "DUORA_TRUSTED_PROXIES=127.0.0.1/32",
            "duora.waitlist.join-rate-limit.capacity=1"})
    @ActiveProfiles("behind-proxy")
    @Import(TestcontainersConfiguration.class)
    class FromTrustedProxy {

        @LocalServerPort
        private int port;

        @Autowired
        private JdbcClient jdbcClient;

        @BeforeEach
        void cleanDatabase() {
            clean(jdbcClient);
        }

        @Test
        void forwardedClientsGetSeparateLimits() throws Exception {
            assertThat(join(port, CLIENT_A, "ana@example.com")).isEqualTo(ACCEPTED);

            assertThat(join(port, CLIENT_B, "bruno@example.com")).isEqualTo(ACCEPTED);
        }

        @Test
        void forwardedClientIsStillLimited() throws Exception {
            assertThat(join(port, CLIENT_A, "ana@example.com")).isEqualTo(ACCEPTED);

            assertThat(join(port, CLIENT_A, "bruno@example.com")).isEqualTo(TOO_MANY_REQUESTS);
        }

        /**
         * O ingress acrescenta o IP real ao fim do header que o cliente mandou; o que vem antes é
         * escrito pelo cliente e não pode escolher o bucket.
         */
        @Test
        void clientCannotChooseItsAddressThroughForwardedChain() throws Exception {
            assertThat(join(port, "203.0.113.1, " + CLIENT_A, "ana@example.com")).isEqualTo(ACCEPTED);

            assertThat(join(port, "203.0.113.2, " + CLIENT_A, "bruno@example.com")).isEqualTo(TOO_MANY_REQUESTS);
        }

    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
            "DUORA_TRUSTED_PROXIES=192.0.2.0/24",
            "duora.waitlist.join-rate-limit.capacity=1"})
    @ActiveProfiles("behind-proxy")
    @Import(TestcontainersConfiguration.class)
    class FromUntrustedPeer {

        @LocalServerPort
        private int port;

        @Autowired
        private JdbcClient jdbcClient;

        @BeforeEach
        void cleanDatabase() {
            clean(jdbcClient);
        }

        @Test
        void forwardedHeaderDoesNotChangeTheClient() throws Exception {
            assertThat(join(port, CLIENT_A, "ana@example.com")).isEqualTo(ACCEPTED);

            assertThat(join(port, CLIENT_B, "bruno@example.com")).isEqualTo(TOO_MANY_REQUESTS);
        }

    }

    private static void clean(JdbcClient jdbcClient) {
        jdbcClient.sql("delete from waitlist_entry").update();
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    private int join(int port, String forwardedFor, String email) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/waitlist"))
                .header("X-Forwarded-For", forwardedFor)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"email": "%s"}
                        """.formatted(email)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

}
