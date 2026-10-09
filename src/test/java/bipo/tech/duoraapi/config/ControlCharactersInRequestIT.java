package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.RateLimitTestSupport.clearBuckets;
import static bipo.tech.duoraapi.TestIdentities.adminBearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * NUL e caracteres de controle no path e na query são erro do cliente (4xx), nunca 500: o PostgreSQL recusa o
 * NUL com exceção. Varre as rotas registradas, com credencial de ADMIN para passar da segurança; o corpo de cada
 * campo já é testado nos *FieldErrorsIT de cada módulo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ControlCharactersInRequestIT {

    private static final List<String> ENCODED_CONTROL_CHARACTERS = List.of("%00", "%01", "%1B", "%7F");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private List<RequestMappingInfoHandlerMapping> handlerMappings;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanDatabase() {
        clearBuckets(jdbcClient);
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @TestFactory
    Stream<DynamicTest> controlCharacterInAPathVariableIsAClientError() {
        return RegisteredRoutes.of(handlerMappings).stream()
                .filter(route -> RegisteredRoutes.patternOf(route).contains("{"))
                .flatMap(route -> ENCODED_CONTROL_CHARACTERS.stream().map(character ->
                        DynamicTest.dynamicTest(route + " with " + character, () -> {
                            String path = RegisteredRoutes.patternOf(route)
                                    .replaceAll("\\{[^}]*}", "a" + character + "b");
                            var response = mockMvc.perform(request(
                                            HttpMethod.valueOf(RegisteredRoutes.methodOf(route)), URI.create(path))
                                            .with(adminBearer("oid-control-admin"))
                                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                    .andReturn().getResponse();

                            assertThat(response.getStatus()).isBetween(400, 499);
                        })));
    }

    @ParameterizedTest
    @CsvSource({
            "/api/events, maxPageSize", "/api/events, pageToken",
            "/api/me/registrations, maxPageSize", "/api/me/registrations, pageToken",
            "/api/me/connections, maxPageSize", "/api/me/connections, pageToken",
            "/api/me/blocked-accounts, maxPageSize", "/api/me/blocked-accounts, pageToken"})
    void controlCharacterInAPagingParameterIsABadRequest(String path, String parameter) throws Exception {
        for (String character : List.of("\u0000", "\u0001", "\u001b", "\u007f")) {
            var response = mockMvc.perform(get(path).param(parameter, "1" + character)
                            .with(adminBearer("oid-control-admin")))
                    .andReturn().getResponse();

            assertThat(response.getStatus()).as(path + "?" + parameter).isEqualTo(400);
        }
    }

}
