package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.TestIdentities.ISSUER;
import static bipo.tech.duoraapi.TestIdentities.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * BFLA: toda rota sob /api/admin exige o papel ADMIN, varrendo as rotas registradas, para que uma rota nova de
 * ADMIN não dependa de alguém lembrar de testá-la. O 403 vem antes do controller, então nada é lido nem
 * gravado. A documentação, que só existe com o perfil api-docs, está em ApiDocsAccessIT. O jwt() pula a
 * validação do token, testada em BearerTokenValidationIT: aqui só o papel importa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminRoleOnEveryAdminRouteIT {

    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final String SECURITY_FORBIDDEN = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<RequestMappingInfoHandlerMapping> handlerMappings;

    /** Sem isso, uma mudança na enumeração faria o teste passar sem testar rota nenhuma. */
    @Test
    void enumerationFindsTheAdminRoutesOfEveryModule() {
        assertThat(adminRoutes()).contains("GET /api/admin/waitlist/stats", "POST /api/admin/events",
                "GET /api/admin/events/{id:[^:]+}", "POST /api/admin/events/{id}:publish",
                "POST /api/admin/events/{id}:cancel", "PUT /api/admin/events/{eventId}/rounds/{number}",
                "GET /api/admin/events/{eventId}/rounds/{number}");
    }

    @TestFactory
    Stream<DynamicTest> everyAdminRouteIsForbiddenToOtherRoles() {
        return adminRoutes().stream().flatMap(route -> Stream.of(
                DynamicTest.dynamicTest(route + " without role", () -> expectForbidden(route, userWithoutRoles())),
                DynamicTest.dynamicTest(route + " with another role", () -> expectForbidden(route, moderator()))));
    }

    private void expectForbidden(String route, RequestPostProcessor caller) throws Exception {
        var response = mockMvc.perform(RegisteredRoutes.requestFor(route).with(caller)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo(SECURITY_FORBIDDEN);
    }

    private List<String> adminRoutes() {
        return RegisteredRoutes.of(handlerMappings).stream()
                .filter(route -> RegisteredRoutes.patternOf(route).startsWith(ADMIN_PREFIX))
                .toList();
    }

    private static RequestPostProcessor userWithoutRoles() {
        return bearer("oid-plain-user");
    }

    private static RequestPostProcessor moderator() {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-moderator"))
                .authorities(new SimpleGrantedAuthority("ROLE_MODERATOR"));
    }

}
