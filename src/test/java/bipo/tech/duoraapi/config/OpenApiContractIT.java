package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import bipo.tech.duoraapi.FieldErrorCode;
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

    /** O correlation ID (docs/adr/0013) sai em toda resposta e no ProblemDetail do 500; a spec diz isso. */
    @Test
    void everyResponseDeclaresTheRequestId() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        var responses = spec.at("/paths").findValues("responses").stream()
                .flatMap(byStatus -> byStatus.properties().stream())
                .toList();

        assertThat(responses).isNotEmpty().allSatisfy(response -> assertThat(response.getValue()
                .at("/headers/" + RequestIdResponseFilter.HEADER + "/$ref").asString())
                .as("resposta %s", response.getKey())
                .isEqualTo("#/components/headers/RequestId"));
        assertThat(spec.at("/components/schemas/ProblemDetail/properties/requestId/pattern").asString())
                .isEqualTo("^[0-9a-f]{32}$");
    }

    /**
     * O 400 de toda operação com corpo declara a lista errors (docs/adr/0018), e os codes da spec são
     * exatamente os do FieldErrorCode: o front gera o tipo deles daqui.
     */
    @Test
    void bodyValidationProblemsDocumentTheFieldErrors() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        List<JsonNode> operationsWithBody = spec.at("/paths").valueStream()
                .flatMap(item -> HTTP_METHODS.stream().filter(item::has).map(item::get))
                .filter(operation -> operation.has("requestBody"))
                .toList();
        JsonNode errors = spec.at("/components/schemas/ValidationProblemDetail/properties/errors");
        JsonNode fieldError = spec.at("/components/schemas/FieldError");

        assertThat(operationsWithBody).isNotEmpty().allSatisfy(operation -> assertThat(operation
                .at("/responses/400/content/application~1problem+json/schema/$ref").asString())
                .as("400 de %s", operation.path("operationId").asString())
                .isEqualTo("#/components/schemas/ValidationProblemDetail"));
        assertThat(errors.at("/items/$ref").asString()).isEqualTo("#/components/schemas/FieldError");
        assertThat(errors.at("/maxItems").asInt()).isEqualTo(RequestBodyProblemHandler.MAX_ERRORS);
        assertThat(fieldError.at("/properties/code/enum").valueStream().map(JsonNode::asString))
                .containsExactly(Stream.of(FieldErrorCode.values()).map(Enum::name).toArray(String[]::new));
        assertThat(fieldError.at("/properties/field/pattern").asString())
                .isEqualTo(RequestBodyProblemHandler.FIELD_NAME_PATTERN);
        assertThat(fieldError.at("/required").valueStream().map(JsonNode::asString)).containsExactly("code");
    }

    /** A conta de quem chama vem do token ou da sessão; um parâmetro na spec convidaria o cliente a mandá-la. */
    @Test
    void noOperationAsksTheClientForTheCallersAccount() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());

        assertThat(spec.findValues("parameters").stream()
                .flatMap(parameters -> parameters.valueStream())
                .map(parameter -> parameter.path("name").asString()))
                .doesNotContain("account");
        assertThat(spec.at("/components/schemas").has("AccountId")).isFalse();
    }

    /** Edição do perfil (docs/adr/0011): o contrato de concorrência aparece inteiro na spec. */
    @Test
    void profileEditDocumentsTheConcurrencyContract() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        JsonNode read = spec.at("/paths/~1api~1me~1profile/get");
        JsonNode edit = spec.at("/paths/~1api~1me~1profile/patch");

        assertThat(read.at("/responses/200/headers/ETag/required").asBoolean()).isTrue();
        assertThat(edit.at("/responses/200/headers/ETag/required").asBoolean()).isTrue();
        assertThat(edit.get("parameters").valueStream()
                .filter(parameter -> parameter.path("name").asString().equals("If-Match"))
                .map(parameter -> parameter.path("in").asString() + ":" + parameter.path("required").asBoolean()))
                .containsExactly("header:true");
        for (int status : new int[] {400, 409, 412, 428}) {
            assertThat(documentsProblem(edit, status)).as("PATCH do perfil documenta o %d", status).isTrue();
        }
    }

    /** Bloqueio e denúncia (docs/adr/0015): erros, cota, Location e paginação aparecem na spec. */
    @Test
    void blockAndReportDocumentTheirContract() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        JsonNode block = spec.at("/paths/~1api~1accounts~1{accountId}:block/post");
        JsonNode list = spec.at("/paths/~1api~1me~1blocked-accounts/get");
        JsonNode file = spec.at("/paths/~1api~1reports/post");
        JsonNode read = spec.at("/paths/~1api~1reports~1{id}/get");

        assertThat(block.at("/responses/204").isMissingNode()).isFalse();
        assertThat(documentsProblem(block, 400)).isTrue();
        assertThat(documentsProblem(block, 404)).isTrue();
        assertThat(list.get("parameters").valueStream()
                .map(parameter -> parameter.path("name").asString() + ":" + parameter.at("/schema/maximum").asString()))
                .contains("maxPageSize:100");
        assertThat(spec.at("/components/schemas/BlockedAccountsResponse/properties/items/maxItems").asInt())
                .isEqualTo(100);
        assertThat(file.at("/responses/201/headers/Location/required").asBoolean()).isTrue();
        assertThat(file.at("/responses/429/headers/Retry-After/required").asBoolean()).isTrue();
        for (int status : new int[] {400, 404, 429, 503}) {
            assertThat(documentsProblem(file, status)).as("POST de denúncia documenta o %d", status).isTrue();
        }
        assertThat(documentsProblem(read, 404)).isTrue();
        assertThat(spec.at("/components/schemas/FileReportRequest/additionalProperties").asBoolean(true)).isFalse();
        assertThat(spec.at("/components/schemas/FileReportRequest/properties/reason/enum").valueStream()
                .map(JsonNode::asString))
                .containsExactly("HARASSMENT", "HATE_SPEECH", "SEXUAL_CONTENT", "VIOLENCE_OR_THREAT", "SCAM_OR_SPAM",
                        "FAKE_PROFILE", "SUSPECTED_MINOR", "OTHER");
        assertThat(spec.at("/components/schemas/FileReportRequest/properties/description/maxLength").asInt())
                .isEqualTo(1000);
    }

    /** Eventos e inscrições (docs/adr/0016): erros, Location, paginação e estados aparecem na spec. */
    @Test
    void eventsAndRegistrationsDocumentTheirContract() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        JsonNode create = spec.at("/paths/~1api~1admin~1events/post");
        JsonNode publish = spec.at("/paths/~1api~1admin~1events~1{id}:publish/post");
        JsonNode list = spec.at("/paths/~1api~1events/get");
        JsonNode register = spec.at("/paths/~1api~1events~1{eventId}~1registration/put");
        JsonNode unregister = spec.at("/paths/~1api~1events~1{eventId}~1registration/delete");

        assertThat(create.at("/responses/201/headers/Location/required").asBoolean()).isTrue();
        assertThat(documentsProblem(create, 400)).isTrue();
        assertThat(spec.at("/components/schemas/CreateEventRequest/additionalProperties").asBoolean(true)).isFalse();
        assertThat(spec.at("/components/schemas/CreateEventRequest/properties/capacity/maximum").asInt())
                .isEqualTo(200);
        for (int status : new int[] {404, 409}) {
            assertThat(documentsProblem(publish, status)).as(":publish documenta o %d", status).isTrue();
        }
        assertThat(list.get("parameters").valueStream().map(parameter -> parameter.path("name").asString()))
                .containsExactlyInAnyOrder("maxPageSize", "pageToken");
        assertThat(list.get("parameters").valueStream()
                .map(parameter -> parameter.path("name").asString() + ":" + parameter.at("/schema/maximum").asString()))
                .contains("maxPageSize:50");
        assertThat(register.at("/responses/201/headers/Location/required").asBoolean()).isTrue();
        assertThat(register.at("/responses/503/headers/Retry-After/required").asBoolean()).isTrue();
        for (int status : new int[] {403, 404, 409, 429, 503}) {
            assertThat(documentsProblem(register, status)).as("PUT da inscrição documenta o %d", status).isTrue();
        }
        for (JsonNode operation : List.of(register, unregister)) {
            assertThat(operation.at("/responses/429/headers/Retry-After/required").asBoolean()).isTrue();
            assertThat(operation.at("/responses/429/headers/Retry-After/schema/maximum").asInt()).isEqualTo(86_400);
        }
        assertThat(documentsProblem(unregister, 429)).isTrue();
        assertThat(documentsProblem(unregister, 503)).isTrue();
        assertThat(unregister.at("/responses/503/headers/Retry-After/required").asBoolean()).isTrue();
        assertThat(register.at("/responses/403/description").asString()).contains("Perfil incompleto", "X-XSRF-TOKEN");
        assertThat(unregister.at("/responses/204").isMissingNode()).isFalse();
        assertThat(spec.at("/components/schemas/AdminEventResponse/properties/status/enum").valueStream()
                .map(JsonNode::asString))
                .containsExactly("DRAFT", "PUBLISHED", "CANCELLED");
        assertThat(spec.at("/components/schemas/EventResponse/properties/status/enum").valueStream()
                .map(JsonNode::asString))
                .containsExactly("PUBLISHED", "CANCELLED");
        assertThat(spec.at("/components/schemas/EventResponse/properties").has("capacity")).isFalse();
    }

    /** Rodadas (docs/adr/0017): o limite por conta ADMIN aparece como 429 com Retry-After e 503. */
    @Test
    void startRoundDocumentsItsRateLimit() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        JsonNode start = spec.at("/paths/~1api~1admin~1events~1{eventId}~1rounds~1{number}/put");

        assertThat(documentsProblem(start, 429)).isTrue();
        assertThat(start.at("/responses/429/headers/Retry-After/required").asBoolean()).isTrue();
        assertThat(start.at("/responses/429/headers/Retry-After/schema/maximum").asInt()).isEqualTo(86_400);
        assertThat(documentsProblem(start, 503)).isTrue();
        assertThat(start.at("/responses/503/headers/Retry-After/required").asBoolean()).isTrue();
    }

    /**
     * O logout é rota do Spring Security, sem controller (docs/adr/0002): a spec o declara à mão, e o que
     * declara é o que a segurança faz. 200 com a URL do Entra com o token CSRF, mesmo sem sessão; 403 sem ele.
     */
    @Test
    void logoutDocumentsWhatSpringSecurityDoes() throws Exception {
        JsonNode spec = jsonMapper.readTree(generatedSpec());
        JsonNode logout = spec.at("/paths/~1logout/post");
        JsonNode schema = spec.at("/components/schemas/LogoutResponse");

        assertThat(logout.path("operationId").asString()).isEqualTo("logout");
        assertThat(logout.at("/tags").valueStream().map(JsonNode::asString)).containsExactly("session");
        assertThat(logout.path("security").isArray()).as("pública: nenhuma credencial").isTrue();
        assertThat(logout.path("security")).isEmpty();
        assertThat(logout.has("requestBody")).isFalse();
        assertThat(logout.at("/responses/200/content/application~1json/schema/$ref").asString())
                .isEqualTo("#/components/schemas/LogoutResponse");
        assertThat(documentsProblem(logout, 403)).isTrue();
        assertThat(documentsProblem(logout, 500)).isTrue();
        assertThat(documentsProblem(logout, 401)).as("sem sessão o logout não é 401").isFalse();
        assertThat(schema.at("/properties/logoutUrl/format").asString()).isEqualTo("uri");
        assertThat(schema.at("/properties/logoutUrl/maxLength").asInt()).isEqualTo(2_048);
        assertThat(schema.at("/required").valueStream().map(JsonNode::asString)).containsExactly("logoutUrl");

        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.logoutUrl").isString());
        mockMvc.perform(post("/logout")).andExpect(status().isForbidden());
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
