package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Toda resposta leva o correlation ID em {@code X-Request-Id}: o trace id W3C da requisição. Ele vem
 * do {@code traceparent} do cliente quando válido; senão o servidor gera um. HTTP de verdade, porque
 * o header precisa sair também nas recusas da segurança, que o Tomcat escreve.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RequestCorrelationIT {

    private static final String REQUEST_ID = "X-Request-Id";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private static final String GENERATED_ID = "^[0-9a-f]{32}$";
    private static final String INVALID_ID = "00000000000000000000000000000000";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void responseCarriesGeneratedRequestIdWhenClientSendsNone() throws Exception {
        var response = get("/actuator/health", null, null);

        assertThat(response.headers().firstValue(REQUEST_ID)).hasValueSatisfying(id ->
                assertThat(id).matches(GENERATED_ID).isNotEqualTo(INVALID_ID));
    }

    @Test
    void eachRequestGetsItsOwnRequestId() throws Exception {
        var first = get("/actuator/health", null, null).headers().firstValue(REQUEST_ID);
        var second = get("/actuator/health", null, null).headers().firstValue(REQUEST_ID);

        assertThat(first).isPresent();
        assertThat(second).isPresent().isNotEqualTo(first);
    }

    @Test
    void validTraceparentBecomesTheRequestId() throws Exception {
        var response = get("/actuator/health", VALID_TRACEPARENT, null);

        assertThat(response.headers().firstValue(REQUEST_ID)).hasValue(TRACE_ID);
    }

    @Test
    void securityRejectionAlsoCarriesTheRequestId() throws Exception {
        var response = get("/api/me", VALID_TRACEPARENT, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(REQUEST_ID)).hasValue(TRACE_ID);
    }

    /** Fora do formato W3C: maiúsculas, id zerado, versão inválida, curto, longo demais ou lixo. */
    @ParameterizedTest
    @ValueSource(strings = {
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e47-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "not-a-traceparent"})
    void invalidTraceparentIsReplacedByGeneratedId(String traceparent) throws Exception {
        var response = get("/actuator/health", traceparent, null);

        assertThat(response.headers().firstValue(REQUEST_ID)).hasValueSatisfying(id ->
                assertThat(id).matches(GENERATED_ID).isNotEqualTo(TRACE_ID).isNotEqualTo(INVALID_ID));
    }

    /** Texto livre não vira trace id: o cliente não escolhe o que entra no log nem no header. */
    @Test
    void clientRequestIdHeaderIsNotEchoed() throws Exception {
        var response = get("/actuator/health", null, TRACE_ID);

        assertThat(response.headers().allValues(REQUEST_ID)).singleElement().satisfies(id ->
                assertThat(id).matches(GENERATED_ID).isNotEqualTo(TRACE_ID));
    }

    private HttpResponse<String> get(String path, String traceparent, String requestId)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        if (requestId != null) {
            request.header(REQUEST_ID, requestId);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

}
