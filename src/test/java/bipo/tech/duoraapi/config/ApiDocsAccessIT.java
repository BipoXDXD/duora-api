package bipo.tech.duoraapi.config;

import static bipo.tech.duoraapi.TestIdentities.adminWithoutIdentity;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Com o perfil api-docs, a spec e o Swagger UI existem, mas só para ADMIN, nas duas portas de entrada
 * (docs/adr/0012). O jwt() do MockMvc pula a validação do token, o que basta para testar papel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(ApiDocsAccessIT.API_DOCS_PROFILE)
@Import(TestcontainersConfiguration.class)
class ApiDocsAccessIT {

    static final String API_DOCS_PROFILE = "api-docs";
    static final String SPEC_PATH = "/api/admin/openapi";
    private static final String SWAGGER_UI_PATH = "/api/admin/swagger-ui.html";
    private static final String SWAGGER_UI_RESOURCES = "/api/admin/swagger-ui/";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {SPEC_PATH, SPEC_PATH + ".yaml", SWAGGER_UI_PATH})
    void anonymousCannotReadTheDocs(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type": "about:blank", "title": "Unauthorized", "status": 401}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {SPEC_PATH, SPEC_PATH + ".yaml", SWAGGER_UI_PATH})
    void regularUserCannotReadTheDocs(String path) throws Exception {
        mockMvc.perform(get(path).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type": "about:blank", "title": "Forbidden", "status": 403}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void adminReadsTheSpec() throws Exception {
        mockMvc.perform(get(SPEC_PATH).with(adminWithoutIdentity()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.paths['/api/waitlist'].post").exists());
    }

    @Test
    void adminOpensTheSwaggerUi() throws Exception {
        mockMvc.perform(get(SWAGGER_UI_PATH).with(adminWithoutIdentity()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith(SWAGGER_UI_RESOURCES)));
    }

    /** Os arquivos do Swagger UI também ficam sob /api/admin, e não em /swagger-ui, que seria só autenticado. */
    @Test
    void swaggerUiResourcesAreAdminOnly() throws Exception {
        mockMvc.perform(get(SWAGGER_UI_RESOURCES + "index.html").with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(SWAGGER_UI_RESOURCES + "index.html").with(adminWithoutIdentity()))
                .andExpect(status().isOk());
    }

}
