package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A spec versionada em docs/openapi.json é a que a aplicação gera (sem drift), e o que ela declara
 * sobre autenticação é o que a segurança aplica (docs/adr/0012). O front gera os tipos dela.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(ApiDocsAccessIT.API_DOCS_PROFILE)
@Import(TestcontainersConfiguration.class)
class OpenApiContractIT {

    private static final Path COMMITTED_SPEC = Path.of("docs", "openapi.json");
    private static final Path GENERATED_SPEC = Path.of("target", "openapi.json");
    private static final String PATH_VARIABLE_VALUE = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";
    private static final String ADMIN_PATH_PREFIX = "/api/admin/";
    private static final List<String> HTTP_METHODS = List.of("get", "put", "post", "delete", "patch");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void committedSpecMatchesTheGeneratedOne() throws Exception {
        String generated = generatedSpec();
        Files.writeString(GENERATED_SPEC, generated, StandardCharsets.UTF_8);

        assertThat(COMMITTED_SPEC)
                .as("spec ausente; gere com ./mvnw verify e copie %s para %s", GENERATED_SPEC, COMMITTED_SPEC)
                .exists();
        assertThat(jsonMapper.readTree(COMMITTED_SPEC.toFile()))
                .as("%s está desatualizada; depois de ./mvnw verify, copie %s para ela e revise o diff",
                        COMMITTED_SPEC, GENERATED_SPEC)
                .isEqualTo(jsonMapper.readTree(generated));
    }

    /**
     * Operação pública declara {@code security: []}; as outras declaram a resposta 401 e recebem 401
     * sem credencial. Assim a spec não promete acesso que a segurança nega, nem o contrário.
     */
    @TestFactory
    Stream<DynamicTest> everyOperationDeclaresTheAuthenticationTheApiEnforces() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        var tests = new ArrayList<DynamicTest>();
        for (var path : spec.get("paths").properties()) {
            for (String method : HTTP_METHODS) {
                JsonNode operation = path.getValue().get(method);
                if (operation != null) {
                    tests.add(DynamicTest.dynamicTest(method.toUpperCase() + " " + path.getKey(),
                            () -> expectDeclaredAuthentication(spec, method, path.getKey(), operation)));
                }
            }
        }
        assertThat(tests).as("a spec não tem operações").isNotEmpty();
        return tests.stream();
    }

    private void expectDeclaredAuthentication(JsonNode spec, String method, String path, JsonNode operation)
            throws Exception {
        JsonNode security = operation.has("security") ? operation.get("security") : spec.get("security");
        boolean isPublic = security != null && security.isEmpty();
        int status = mockMvc.perform(request(HttpMethod.valueOf(method.toUpperCase()),
                                path.replaceAll("\\{[^}]*}", PATH_VARIABLE_VALUE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getStatus();

        if (isPublic) {
            assertThat(status).as("operação pública não pede credencial").isNotEqualTo(401);
        } else {
            assertThat(security).as("operação protegida declara o esquema de autenticação").isNotNull();
            assertThat(status).isEqualTo(401);
            assertThat(documentsProblem(operation, 401)).as("operação protegida documenta o 401").isTrue();
        }
        assertThat(documentsProblem(operation, 500)).as("toda operação documenta o 500 em ProblemDetail").isTrue();
        if (operation.has("requestBody")) {
            assertThat(documentsProblem(operation, 415)).as("operação com corpo documenta o 415").isTrue();
        }
        if (path.startsWith(ADMIN_PATH_PREFIX)) {
            assertThat(documentsProblem(operation, 403)).as("operação de ADMIN documenta o 403").isTrue();
        }
    }

    private static boolean documentsProblem(JsonNode operation, int status) {
        return operation.at("/responses/" + status + "/content").has(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    private String generatedSpec() throws Exception {
        String body = mockMvc.perform(get(ApiDocsAccessIT.SPEC_PATH)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.endsWith("\n") ? body : body + "\n";
    }

}
