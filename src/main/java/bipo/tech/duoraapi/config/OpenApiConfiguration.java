package bipo.tech.duoraapi.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * O que a spec gerada pelo springdoc não sabe sozinha (docs/adr/0012): as duas portas de entrada como
 * esquemas de segurança, exigidas por padrão, e as respostas que valem para toda operação e não saem
 * do controller (401 e 403 da segurança, 415, 500), em {@code ProblemDetail} (docs/adr/0005), além
 * do correlation ID em toda resposta (docs/adr/0013). Operação pública declara {@code security: []}
 * no próprio controller.
 *
 * <p>As rotas do Spring Security (o {@code POST /logout}) não passam por controller e o springdoc não as
 * vê: entram aqui, à mão, antes das respostas comuns, para recebê-las também.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String BEARER_SCHEME = "bearer";
    private static final String SESSION_SCHEME = "session";
    private static final String PROBLEM_SCHEMA = "ProblemDetail";
    private static final String PROBLEM_REF = "#/components/schemas/" + PROBLEM_SCHEMA;
    private static final String LOGOUT_PATH = "/logout";
    private static final String LOGOUT_SCHEMA = "LogoutResponse";
    private static final String SESSION_TAG = "session";
    /** A URL de logout do Entra (host, client_id e o redirect de volta, codificados) cabe folgada em 2 KB. */
    private static final int LOGOUT_URL_MAX_LENGTH = 2_048;
    private static final String REQUEST_ID_HEADER = "RequestId";
    private static final String REQUEST_ID_REF = "#/components/headers/" + REQUEST_ID_HEADER;

    /** O trace id W3C, em hexadecimal minúsculo (docs/adr/0013). */
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";
    private static final int TRACE_ID_LENGTH = 32;

    private static final PathPattern ADMIN_ROUTES =
            PathPatternParser.defaultInstance.parse(SecurityConfiguration.ADMIN_ROUTES);

    /** Teto dos textos do ProblemDetail: só título e detalhe curtos, nunca stack trace. */
    private static final int PROBLEM_TEXT_MAX_LENGTH = 1_000;
    private static final int MIN_HTTP_STATUS = 100;
    private static final int MAX_HTTP_STATUS = 599;

    @Bean
    OpenAPI duoraOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Duora API")
                        .version("0.1.0")
                        .description("""
                                API do Duora. Duas portas de entrada com as mesmas regras de rota: \
                                cookie de sessão do front web (BFF), com o token CSRF do cookie XSRF-TOKEN \
                                no header X-XSRF-TOKEN em toda mutação, e Authorization: Bearer com o \
                                access token do Entra External ID. Erros em application/problem+json."""))
                // Relativo: a mesma spec vale em qualquer ambiente, e o cliente gerado usa a própria origem.
                // x-internal false: API exposta à internet (front web e app), não só à rede interna.
                .servers(List.of(new Server().url("/").extensions(Map.of("x-internal", false))))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        Access token da duora-api emitido pelo Entra External ID, validado \
                                        conforme a RFC8725: algoritmo fixo (RS256) e iss, aud, exp e oid \
                                        obrigatórios."""))
                        .addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(WebSessionConfiguration.SESSION_COOKIE_NAME)
                                .description("Sessão do front web, criada pelo login em /oauth2/authorization/entra"))
                        .addSchemas(PROBLEM_SCHEMA, problemDetailSchema())
                        .addHeaders(REQUEST_ID_HEADER, new Header()
                                .required(true)
                                .description("""
                                        Correlation ID da requisição (trace id W3C); informe-o ao reportar \
                                        um erro""")
                                .schema(traceIdSchema())))
                // Qualquer uma das duas portas basta; negar por padrão vale também na spec.
                .security(List.of(
                        new SecurityRequirement().addList(BEARER_SCHEME),
                        new SecurityRequirement().addList(SESSION_SCHEME)));
    }

    /**
     * Roda antes de {@link #crossCuttingResponses()}, que acrescenta o 500 e o X-Request-Id a toda operação.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    OpenApiCustomizer springSecurityRoutes() {
        return openApi -> {
            openApi.getComponents().addSchemas(LOGOUT_SCHEMA, logoutResponseSchema());
            openApi.getPaths().addPathItem(LOGOUT_PATH, new PathItem().post(logoutOperation()));
        };
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    OpenApiCustomizer crossCuttingResponses() {
        return openApi -> openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
                .forEach((method, operation) -> documentCrossCuttingResponses(path, method, operation)));
    }

    private static void documentCrossCuttingResponses(String path, PathItem.HttpMethod method, Operation operation) {
        documentStatusResponses(path, method, operation);
        // Por último, para valer também para as respostas acrescentadas acima.
        operation.getResponses().values().forEach(response -> response.addHeaderObject(RequestIdResponseFilter.HEADER,
                new Header().$ref(REQUEST_ID_REF)));
    }

    private static void documentStatusResponses(String path, PathItem.HttpMethod method, Operation operation) {
        // ProblemDetailErrorController: só status e título, a causa fica no log.
        operation.getResponses().addApiResponse("500", problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro inesperado"));
        if (operation.getRequestBody() != null) {
            operation.getResponses().addApiResponse("415",
                    problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Corpo em outro formato que não application/json"));
        }
        if (isPublic(operation)) {
            return;
        }
        operation.getResponses().addApiResponse("401", problem(HttpStatus.UNAUTHORIZED, "Sem credencial válida"));
        if (ADMIN_ROUTES.matches(PathContainer.parsePath(path))) {
            operation.getResponses().addApiResponse("403", problem(HttpStatus.FORBIDDEN, "Sem o papel ADMIN"));
        } else if (method != PathItem.HttpMethod.GET) {
            // Operação com um 403 próprio (regra de negócio) o declara já citando o CSRF; não o sobrescreva.
            operation.getResponses().putIfAbsent("403",
                    problem(HttpStatus.FORBIDDEN, "Sessão web sem o token CSRF no header X-XSRF-TOKEN"));
        }
    }

    /**
     * O logout da cadeia de sessão (WebLoginConfiguration, docs/adr/0002). Público: o LogoutFilter roda
     * antes da autorização, então sem sessão (mas com o token CSRF) a resposta também é 200, e não 401.
     * A cadeia bearer não o conhece: com Authorization: Bearer a rota não existe.
     */
    private static Operation logoutOperation() {
        return new Operation()
                .operationId("logout")
                .addTagsItem(SESSION_TAG)
                .summary("Encerra a sessão")
                .description("""
                        Encerra a sessão web aqui, apagando-a do servidor, e devolve a URL de logout do \
                        Entra, para o front navegar até ela e sair também de lá. Exige o token CSRF no \
                        header X-XSRF-TOKEN. Sem sessão ativa a resposta é a mesma. A URL leva só \
                        client_id e post_logout_redirect_uri, nunca o ID token.""")
                .security(List.of())
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse()
                                .description("Sessão encerrada; navegue até logoutUrl")
                                .content(new Content().addMediaType(MediaType.APPLICATION_JSON_VALUE,
                                        new io.swagger.v3.oas.models.media.MediaType()
                                                .schema(new Schema<>().$ref("#/components/schemas/" + LOGOUT_SCHEMA)))))
                        .addApiResponse("403", problem(HttpStatus.FORBIDDEN,
                                "Sem o token CSRF no header X-XSRF-TOKEN; a sessão continua ativa")));
    }

    private static Schema<?> logoutResponseSchema() {
        return new ObjectSchema()
                .addProperty("logoutUrl", new StringSchema().format("uri").maxLength(LOGOUT_URL_MAX_LENGTH)
                        .description("URL de logout do Entra; o front navega até ela depois do 200"))
                .required(List.of("logoutUrl"));
    }

    private static boolean isPublic(Operation operation) {
        return operation.getSecurity() != null && operation.getSecurity().isEmpty();
    }

    private static ApiResponse problem(HttpStatus status, String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().$ref(PROBLEM_REF))));
    }

    /**
     * O formato que a API de fato escreve (RFC 9457). Substitui o que o springdoc deduz da classe do
     * Spring, que mostraria o mapa interno "properties" como campo.
     */
    private static Schema<?> problemDetailSchema() {
        return new ObjectSchema()
                .description("Erro no formato RFC 9457. Sem stack trace, SQL nem nome de classe.")
                .addProperty("type", new StringSchema().format("uri-reference").maxLength(PROBLEM_TEXT_MAX_LENGTH))
                .addProperty("title", new StringSchema().maxLength(PROBLEM_TEXT_MAX_LENGTH))
                .addProperty("status", new IntegerSchema().format("int32")
                        .minimum(BigDecimal.valueOf(MIN_HTTP_STATUS))
                        .maximum(BigDecimal.valueOf(MAX_HTTP_STATUS)))
                .addProperty("detail", new StringSchema().maxLength(PROBLEM_TEXT_MAX_LENGTH))
                .addProperty("instance", new StringSchema().format("uri-reference").maxLength(PROBLEM_TEXT_MAX_LENGTH))
                // Só no 500: o mesmo valor do header X-Request-Id, para quem reporta o erro.
                .addProperty("requestId", traceIdSchema()
                        .description("Correlation ID, o mesmo do header X-Request-Id; vem nos erros inesperados (500)"))
                // Sem "type", vale about:blank (RFC 9457); o Spring o omite nesse caso.
                .required(List.of("title", "status"));
    }

    private static Schema<String> traceIdSchema() {
        return new StringSchema().pattern(TRACE_ID_PATTERN).minLength(TRACE_ID_LENGTH).maxLength(TRACE_ID_LENGTH);
    }

}
