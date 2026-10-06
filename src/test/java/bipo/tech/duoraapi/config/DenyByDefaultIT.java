package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
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

    private static final String PATH_VARIABLE_VALUE = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";
    private static final String WILDCARD_SEGMENT_VALUE = "any";
    private static final String INVALID_BEARER_TOKEN = "Bearer not-a-jwt";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<RequestMappingInfoHandlerMapping> handlerMappings;

    /** Sem isso, uma mudança na enumeração faria o teste passar sem testar rota nenhuma. */
    @Test
    void enumerationFindsControllerActuatorAndPublicRoutes() {
        assertThat(registeredRoutes())
                .contains("GET /api/me", "GET /api/admin/waitlist/stats", "GET /actuator/health/**")
                .containsAll(PUBLIC_ROUTES);
    }

    @TestFactory
    Stream<DynamicTest> everyRouteOutsideAllowlistRejectsRequestsWithoutCredentials() {
        return registeredRoutes().stream()
                .filter(route -> !PUBLIC_ROUTES.contains(route))
                .flatMap(route -> Stream.of(
                        DynamicTest.dynamicTest(route + " without credentials",
                                () -> expectUnauthorized(requestFor(route).with(csrf()))),
                        DynamicTest.dynamicTest(route + " with invalid bearer token",
                                () -> expectUnauthorized(requestFor(route)
                                        .header(HttpHeaders.AUTHORIZATION, INVALID_BEARER_TOKEN)))));
    }

    private void expectUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        var response = mockMvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
    }

    /** "MÉTODO /padrão"; mapeamento sem método declarado aceita todos e entra como GET. */
    private Set<String> registeredRoutes() {
        var routes = new TreeSet<String>();
        for (var mapping : handlerMappings) {
            for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
                Collection<RequestMethod> methods = info.getMethodsCondition().getMethods();
                Collection<RequestMethod> declared = methods.isEmpty() ? List.of(RequestMethod.GET) : methods;
                for (String pattern : info.getPatternValues()) {
                    declared.forEach(method -> routes.add(method + " " + pattern));
                }
            }
        }
        return routes;
    }

    private static MockHttpServletRequestBuilder requestFor(String route) {
        String[] methodAndPattern = route.split(" ", 2);
        String path = methodAndPattern[1]
                .replaceAll("\\{[^}]*}", PATH_VARIABLE_VALUE)
                .replace("**", WILDCARD_SEGMENT_VALUE)
                .replace("*", WILDCARD_SEGMENT_VALUE);
        return request(HttpMethod.valueOf(methodAndPattern[0]), path);
    }

}
