package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Negar por padrão: toda rota registrada, inclusive as do actuator e as que ainda vão surgir, exige
 * credencial nas duas portas de entrada, salvo as rotas públicas listadas aqui de propósito.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DenyByDefaultIT {

    /** Cada entrada é uma decisão de deixar a rota pública; o motivo fica em SecurityConfiguration. */
    private static final Set<String> PUBLIC_ROUTES = Set.of("POST /api/waitlist", "GET /actuator/health");

    private static final String INVALID_BEARER_TOKEN = "Bearer not-a-jwt";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<RequestMappingInfoHandlerMapping> handlerMappings;

    /** Sem isso, uma mudança na enumeração faria o teste passar sem testar rota nenhuma. */
    @Test
    void enumerationFindsControllerActuatorAndPublicRoutes() {
        assertThat(registeredRoutes())
                .contains("GET /api/me", "GET /api/admin/waitlist/stats", "GET /actuator/health/**",
                        "POST /api/accounts/{accountId}:block", "GET /api/me/blocked-accounts", "POST /api/reports",
                        "GET /api/reports/{id}", "PUT /api/admin/events/{eventId}/rounds/{number}",
                        "GET /api/events/{eventId}/rounds/{number}/pairing",
                        "PUT /api/events/{eventId}/rounds/{number}/decision",
                        "GET /api/events/{eventId}/rounds/{number}/decision", "GET /api/me/connections",
                        "GET /api/events/{eventId}/rounds/{number}/chat",
                        "GET /api/events/{eventId}/rounds/{number}/chat/messages",
                        "POST /api/events/{eventId}/rounds/{number}/chat/messages",
                        "GET /api/events/{eventId}/rounds/{number}/chat/messages/{seq:[^:]+}",
                        "POST /api/events/{eventId}/rounds/{number}/chat/messages/{seq}:report")
                .containsAll(PUBLIC_ROUTES);
    }

    @TestFactory
    Stream<DynamicTest> everyRouteOutsideAllowlistRejectsRequestsWithoutCredentials() {
        return registeredRoutes().stream()
                .filter(route -> !PUBLIC_ROUTES.contains(route))
                .flatMap(route -> Stream.of(
                        DynamicTest.dynamicTest(route + " without credentials",
                                () -> expectUnauthorized(RegisteredRoutes.requestFor(route).with(csrf()))),
                        DynamicTest.dynamicTest(route + " with invalid bearer token",
                                () -> expectUnauthorized(RegisteredRoutes.requestFor(route)
                                        .header(HttpHeaders.AUTHORIZATION, INVALID_BEARER_TOKEN)))));
    }

    private void expectUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        var response = mockMvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
    }

    private Set<String> registeredRoutes() {
        return RegisteredRoutes.of(handlerMappings);
    }

}
