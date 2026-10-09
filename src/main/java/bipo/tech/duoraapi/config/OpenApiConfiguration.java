package bipo.tech.duoraapi.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import io.swagger.v3.oas.models.media.ArraySchema;
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

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.RefusalReason;

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
    static final String PROBLEM_SCHEMA = "ProblemDetail";
    private static final String PROBLEM_REF = ApiSchemaConventions.PROBLEM_SCHEMA;
    private static final String VALIDATION_PROBLEM_SCHEMA = "ValidationProblemDetail";
    private static final String VALIDATION_PROBLEM_REF = "#/components/schemas/" + VALIDATION_PROBLEM_SCHEMA;
    private static final String FIELD_ERROR_SCHEMA = "FieldError";
    private static final String REFUSAL_PROBLEM_SCHEMA = "RefusalProblemDetail";
    private static final String REFUSAL_PROBLEM_REF = "#/components/schemas/" + REFUSAL_PROBLEM_SCHEMA;
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
    static final int PROBLEM_TEXT_MAX_LENGTH = 1_000;
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
        return openApi -> {
            // Aqui, e não no bean OpenAPI: lá o springdoc descarta os schemas que nenhuma anotação
            // referencia, e só o documentFieldErrors abaixo passa a referenciar estes.
            openApi.getComponents()
                    .addSchemas(VALIDATION_PROBLEM_SCHEMA, validationProblemDetailSchema())
                    .addSchemas(FIELD_ERROR_SCHEMA, fieldErrorSchema())
                    .addSchemas(REFUSAL_PROBLEM_SCHEMA, refusalProblemDetailSchema());
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap()
                    .forEach((method, operation) -> documentCrossCuttingResponses(path, method, operation)));
        };
    }

    private static void documentCrossCuttingResponses(String path, PathItem.HttpMethod method, Operation operation) {
        documentStatusResponses(path, method, operation);
        // Por último, para valer também para as respostas acrescentadas acima.
        operation.getResponses().values().forEach(response -> response.addHeaderObject(RequestIdResponseFilter.HEADER,
                new Header().$ref(REQUEST_ID_REF)));
    }

    private static void documentStatusResponses(String path, PathItem.HttpMethod method, Operation operation) {
        // ProblemDetailErrorController: só status e título, a causa fica no log.
        operation.getResponses().addApiResponse("500", problem("Erro inesperado"));
        if (operation.getRequestBody() != null) {
            operation.getResponses().addApiResponse("415",
                    problem("Corpo em outro formato que não application/json"));
            documentFieldErrors(operation);
        }
        if (isPublic(operation)) {
            return;
        }
        // Antes dos 403 da segurança, abaixo: só o 403 declarado pelo controller é de regra de negócio.
        documentRefusals(operation);
        operation.getResponses().addApiResponse("401", problem("Sem credencial válida"));
        if (ADMIN_ROUTES.matches(PathContainer.parsePath(path))) {
            operation.getResponses().addApiResponse("403", problem("Sem o papel ADMIN"));
        } else if (method != PathItem.HttpMethod.GET) {
            // Operação com um 403 próprio (regra de negócio) o declara já citando o CSRF; não o sobrescreva.
            operation.getResponses().putIfAbsent("403",
                    problem("Sessão web sem o token CSRF no header X-XSRF-TOKEN"));
        }
    }

    /**
     * RequestBodyProblemHandler: o 400 de uma operação com corpo diz o campo e o motivo em errors
     * (docs/adr/0018). A descrição do controller fica; só o schema muda.
     */
    private static void documentFieldErrors(Operation operation) {
        ApiResponse badRequest = operation.getResponses().get("400");
        String description = badRequest == null ? "Corpo inválido" : badRequest.getDescription();
        operation.getResponses().addApiResponse("400", new ApiResponse()
                .description(description)
                .content(problemContent(VALIDATION_PROBLEM_REF)));
    }

    /**
     * RefusalProblemHandler: o 409 e o 403 de regra de negócio dizem o motivo em reason (docs/adr/0020). A
     * descrição do controller fica; só o schema muda. Operação pública não tem 403 de regra, que depende de
     * quem chama.
     */
    private static void documentRefusals(Operation operation) {
        Stream.of("403", "409")
                .map(operation.getResponses()::get)
                .filter(Objects::nonNull)
                .forEach(response -> response.content(problemContent(REFUSAL_PROBLEM_REF)));
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
                        .addApiResponse("403",
                                problem("Sem o token CSRF no header X-XSRF-TOKEN; a sessão continua ativa")));
    }

    private static Schema<?> logoutResponseSchema() {
        var schema = new ObjectSchema();
        schema.addProperty("logoutUrl", new StringSchema().format("uri").maxLength(LOGOUT_URL_MAX_LENGTH)
                .description("URL de logout do Entra; o front navega até ela depois do 200"));
        schema.setRequired(List.of("logoutUrl"));
        return schema;
    }

    private static boolean isPublic(Operation operation) {
        return operation.getSecurity() != null && operation.getSecurity().isEmpty();
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(problemContent(PROBLEM_REF));
    }

    private static Content problemContent(String schemaRef) {
        return new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new io.swagger.v3.oas.models.media.MediaType().schema(new Schema<>().$ref(schemaRef)));
    }

    /**
     * O formato que a API de fato escreve (RFC 9457). Substitui o que o springdoc deduz da classe do
     * Spring, que mostraria o mapa interno "properties" como campo.
     */
    private static Schema<?> problemDetailSchema() {
        // Os métodos fluentes do swagger-models devolvem o tipo raw Schema; por isso a configuração
        // vai em chamadas separadas sobre o ObjectSchema tipado, sem supressão de aviso.
        var schema = new ObjectSchema();
        schema.setDescription("Erro no formato RFC 9457. Sem stack trace, SQL nem nome de classe.");
        schema.addProperty("type",
                new StringSchema().format("uri-reference").maxLength(PROBLEM_TEXT_MAX_LENGTH));
        schema.addProperty("title", new StringSchema().maxLength(PROBLEM_TEXT_MAX_LENGTH));
        schema.addProperty("status", new IntegerSchema().format("int32")
                .minimum(BigDecimal.valueOf(MIN_HTTP_STATUS))
                .maximum(BigDecimal.valueOf(MAX_HTTP_STATUS)));
        schema.addProperty("detail", new StringSchema().maxLength(PROBLEM_TEXT_MAX_LENGTH));
        schema.addProperty("instance",
                new StringSchema().format("uri-reference").maxLength(PROBLEM_TEXT_MAX_LENGTH));
        // Só no 500: o mesmo valor do header X-Request-Id, para quem reporta o erro.
        var requestId = traceIdSchema();
        requestId.setDescription("Correlation ID, o mesmo do header X-Request-Id; vem nos erros inesperados (500)");
        schema.addProperty("requestId", requestId);
        // Sem "type", vale about:blank (RFC 9457); o Spring o omite nesse caso.
        schema.setRequired(List.of("title", "status"));
        return schema;
    }

    /**
     * O ProblemDetail dos 400 de validação do corpo, com o membro de extensão errors (RFC 9457, seção
     * 3.2). errors é opcional no schema porque um 400 de fora do controller, como a recusa do firewall
     * do Spring Security, chega sem ele.
     */
    private static Schema<?> validationProblemDetailSchema() {
        return problemDetailSchema()
                .description("""
                        Erro de validação do corpo no formato RFC 9457. Além do detail, em inglês e para \
                        pessoas, errors diz a máquinas qual campo falhou e por quê, sem repetir o valor.""")
                .addProperty(RequestBodyProblemHandler.ERRORS_PROPERTY, new ArraySchema()
                        .items(new Schema<>().$ref("#/components/schemas/" + FIELD_ERROR_SCHEMA))
                        .maxItems(RequestBodyProblemHandler.MAX_ERRORS)
                        .description("Um item por campo recusado, ordenados por campo"));
    }

    /**
     * O ProblemDetail das recusas de regra de negócio, com o membro de extensão reason (RFC 9457, seção 3.2).
     * reason é opcional porque o mesmo status também sai sem ele: o 403 do CSRF e o 409 de duas ações
     * simultâneas no mesmo evento.
     */
    private static Schema<?> refusalProblemDetailSchema() {
        return problemDetailSchema()
                .description("""
                        Ação recusada pela regra de negócio, no formato RFC 9457. Além do detail, em inglês e \
                        para pessoas, reason diz a máquinas o motivo. Trate um reason desconhecido, ou a \
                        falta dele, como recusa genérica do status.""")
                .addProperty(RefusalProblemHandler.REASON_PROPERTY, new StringSchema()
                        ._enum(Stream.of(RefusalReason.values()).map(Enum::name).toList())
                        .description("""
                                EVENT_NOT_PUBLISHED: o evento é rascunho. EVENT_ALREADY_PUBLISHED: já \
                                publicado. EVENT_CANCELLED: cancelado. EVENT_STARTED: já começou. EVENT_ENDED: \
                                já acabou. EVENT_FULL: sem vagas. EVENT_NOT_UNDERWAY: fora do horário ou não \
                                publicado, para iniciar rodada. ROUND_OUT_OF_SEQUENCE: a rodada anterior não \
                                existe. PROFILE_INCOMPLETE (403): falta nome, data de nascimento ou região. \
                                UNDERAGE (403): menor de 18 anos. BIRTH_DATE_ALREADY_SET: a data de \
                                nascimento não muda. DECISION_ALREADY_MADE: a decisão da rodada é final. \
                                CHAT_CLOSED: o chat da rodada não aceita mais mensagens, sem dizer por quê. \
                                IDEMPOTENCY_KEY_REUSED: a Idempotency-Key já foi usada com outro conteúdo."""));
    }

    private static Schema<?> fieldErrorSchema() {
        var schema = new ObjectSchema();
        schema.setDescription(
                "Um campo recusado e o motivo. Trate um code desconhecido como erro genérico do campo.");
        schema.addProperty("field", new StringSchema()
                .pattern(RequestBodyProblemHandler.FIELD_NAME_PATTERN)
                .maxLength(RequestBodyProblemHandler.FIELD_NAME_MAX_LENGTH)
                .description("""
                        Nome da propriedade no corpo JSON, como o cliente a enviou. Ausente quando o \
                        erro é do corpo inteiro (MALFORMED_BODY) ou numa chave desconhecida fora do \
                        formato de nome."""));
        schema.addProperty("code", new StringSchema()
                ._enum(Stream.of(FieldErrorCode.values()).map(Enum::name).toList())
                .description("""
                        REQUIRED: ausente, null, vazio ou apagado onde é obrigatório. TOO_SHORT e \
                        TOO_LONG: abaixo de minLength ou acima de maxLength. BELOW_MINIMUM e \
                        ABOVE_MAXIMUM: número ou instante fora da faixa (em birthDate, ABOVE_MAXIMUM é \
                        menor de idade e BELOW_MINIMUM idade implausível). INVALID_FORMAT: tipo ou \
                        formato errado. UNSUPPORTED_VALUE: fora da lista fechada. FORBIDDEN_CHARACTER: \
                        controle, invisível ou espaço especial. SELF_REFERENCE: a própria conta. \
                        UNKNOWN_FIELD: chave que o corpo não aceita. MALFORMED_BODY: o corpo não é um \
                        objeto JSON legível."""));
        schema.setRequired(List.of("code"));
        return schema;
    }

    private static Schema<String> traceIdSchema() {
        var schema = new StringSchema();
        schema.setPattern(TRACE_ID_PATTERN);
        schema.setMinLength(TRACE_ID_LENGTH);
        schema.setMaxLength(TRACE_ID_LENGTH);
        return schema;
    }

}
