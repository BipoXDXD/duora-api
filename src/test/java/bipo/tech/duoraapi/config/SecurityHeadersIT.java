package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.TestIdentities.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Headers de proteção nas respostas com dado pessoal e nas recusas: sem cache, sem sniffing, sem moldura e com
 * HSTS em HTTPS. Sem CORS: o front é servido pela mesma origem (docs/adr/0002), então nenhuma origem de fora
 * recebe permissão de ler as respostas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityHeadersIT {

    private static final String PROFILE_PATH = "/api/me/profile";
    private static final String FOREIGN_ORIGIN = "https://evil.example";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @Test
    void personalDataIsNeitherCachedNorSniffedNorFramed() throws Exception {
        var response = mockMvc.perform(get(PROFILE_PATH).secure(true).with(ana()))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertProtectionHeaders(response);
    }

    @Test
    void refusalCarriesTheSameHeaders() throws Exception {
        var response = mockMvc.perform(get(PROFILE_PATH).secure(true)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertProtectionHeaders(response);
    }

    @Test
    void foreignOriginIsNotAllowedToReadTheResponse() throws Exception {
        var response = mockMvc.perform(get(PROFILE_PATH).with(ana()).header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN))
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @Test
    void foreignOriginPreflightIsNotAllowed() throws Exception {
        var response = mockMvc.perform(options(PROFILE_PATH)
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).isNull();
    }

    private static void assertProtectionHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Strict-Transport-Security")).contains("max-age=");
    }

    private static RequestPostProcessor ana() {
        return bearer("oid-ana");
    }

}
