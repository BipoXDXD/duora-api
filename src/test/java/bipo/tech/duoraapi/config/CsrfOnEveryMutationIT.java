package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.RateLimitTestSupport.clearBuckets;
import static bipo.tech.duoraapi.TestIdentities.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * CSRF em toda mutação da sessão web (docs/adr/0002), varrendo as rotas registradas: uma rota nova de escrita
 * entra no teste sozinha. A sessão é de ADMIN para que o papel nunca seja o motivo do 403; o par "com token
 * passa da segurança" prova que foi a falta do token que barrou. O logout não é rota do MVC: está em WebLoginIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CsrfOnEveryMutationIT {

    /** Cada entrada é uma decisão de dispensar o CSRF; o motivo fica em SecurityConfiguration. */
    private static final Set<String> CSRF_EXEMPT_ROUTES = Set.of("POST /api/waitlist");

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** A recusa da segurança: sem detail nem reason, ao contrário das recusas de regra de negócio. */
    private static final String SECURITY_FORBIDDEN = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<RequestMappingInfoHandlerMapping> handlerMappings;

    @Autowired
    private JdbcClient jdbcClient;

    /** As chamadas com token chegam aos controllers: abrem a conta e gastam limites. */
    @AfterEach
    void cleanDatabase() {
        clearBuckets(jdbcClient);
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    /** Sem isso, uma mudança na enumeração faria o teste passar sem testar rota nenhuma. */
    @Test
    void enumerationFindsTheMutationsOfEveryModule() {
        assertThat(mutatingRoutes())
                .contains("PATCH /api/me/profile", "POST /api/accounts/{accountId}:block",
                        "POST /api/accounts/{accountId}:unblock", "POST /api/reports",
                        "POST /api/admin/events", "POST /api/admin/events/{id}:publish",
                        "POST /api/admin/events/{id}:cancel", "PUT /api/events/{eventId}/registration",
                        "DELETE /api/events/{eventId}/registration", "PUT /api/admin/events/{eventId}/rounds/{number}",
                        "PUT /api/events/{eventId}/rounds/{number}/decision")
                .doesNotContainAnyElementsOf(CSRF_EXEMPT_ROUTES);
    }

    @TestFactory
    Stream<DynamicTest> everyMutationOfTheWebSessionRequiresTheCsrfToken() {
        return mutatingRoutes().stream().flatMap(route -> Stream.of(
                DynamicTest.dynamicTest(route + " without CSRF token", () -> {
                    var response = mockMvc.perform(RegisteredRoutes.requestFor(route).with(adminWebSession())
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse();

                    assertThat(response.getStatus()).isEqualTo(403);
                    assertThat(response.getContentAsString()).isEqualTo(SECURITY_FORBIDDEN);
                }),
                DynamicTest.dynamicTest(route + " with CSRF token", () -> {
                    var response = mockMvc.perform(RegisteredRoutes.requestFor(route).with(adminWebSession())
                                    .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse();

                    assertThat(response.getContentAsString()).isNotEqualTo(SECURITY_FORBIDDEN);
                })));
    }

    private List<String> mutatingRoutes() {
        return RegisteredRoutes.of(handlerMappings).stream()
                .filter(route -> MUTATING_METHODS.contains(RegisteredRoutes.methodOf(route)))
                .filter(route -> !CSRF_EXEMPT_ROUTES.contains(route))
                .toList();
    }

    private static RequestPostProcessor adminWebSession() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", "oid-csrf-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

}
