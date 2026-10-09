package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.TestIdentities.adminWithoutIdentity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Sem o perfil api-docs, como em produção, a documentação nem existe: nem um ADMIN a encontra
 * (docs/adr/0012).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocsDisabledByDefaultIT {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/openapi", "/api/admin/openapi.yaml", "/api/admin/swagger-ui.html",
            "/api/admin/swagger-ui/index.html", "/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"})
    void docsDoNotExistWithoutTheProfile(String path) throws Exception {
        mockMvc.perform(get(path).with(adminWithoutIdentity()))
                .andExpect(status().isNotFound());
    }

}
