package bipo.tech.duoraapi.events.api;

import static bipo.tech.duoraapi.events.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.events.api.ApiSchemas.PROBLEM_SCHEMA;

import java.net.URI;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.config.AccountRateLimit;
import bipo.tech.duoraapi.events.application.RegistrationOutcome;
import bipo.tech.duoraapi.events.application.RegistrationService;
import bipo.tech.duoraapi.events.application.ResultPage;
import bipo.tech.duoraapi.events.domain.RegisteredEvent;
import bipo.tech.duoraapi.identity.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * A inscrição de quem chama, como sub-recurso singular do evento (docs/adr/0016): não há id de
 * inscrição na URL, então não há como apontar para a de outra pessoa. PUT cria ou devolve a mesma
 * (idempotente pela chave evento + conta); DELETE é idempotente, 204 também na repetição. Os dois gastam
 * o mesmo limite por conta antes de tocar o banco, inclusive nas repetições idempotentes: o custo é o lock
 * do evento, e não o efeito (docs/adr/0016).
 */
@RestController
@Tag(name = "events", description = "Eventos publicados e as inscrições de quem está logado")
class RegistrationController {

    static final String PATH = EventController.PATH + "/{eventId}/registration";
    static final String MINE_PATH = "/api/me/registrations";

    private static final String EVENT_ID_DESCRIPTION = "Id do evento";

    private final RegistrationService registrations;
    private final AccountRateLimit rateLimit;

    RegistrationController(RegistrationService registrations,
            @Qualifier(RegistrationRateLimitConfiguration.BEAN_NAME) AccountRateLimit rateLimit) {
        this.registrations = registrations;
        this.rateLimit = rateLimit;
    }

    /** 201 com Location na primeira vez; 200 com a mesma inscrição nas repetições. */
    @PutMapping(PATH)
    @Operation(operationId = "registerForEvent", summary = "Inscreve quem chama no evento",
            description = "Sem corpo. Idempotente pela chave evento + conta: repetir devolve a mesma inscrição, com "
                    + "a mesma data, por isso dispensa If-Match e Idempotency-Key (docs/adr/0016). Exige perfil "
                    + "completo e 18 anos. Cada chamada, repetida ou não, gasta o limite da conta, compartilhado "
                    + "com cancelMyRegistration: 60 por hora, repostas aos poucos.")
    @ApiResponse(responseCode = "201", description = "A inscrição criada",
            headers = @Header(name = "Location", required = true, description = "Endereço da inscrição",
                    schema = @Schema(type = "string", format = "uri", maxLength = ApiSchemas.LOCATION_MAX_LENGTH)))
    @ApiResponse(responseCode = "200", description = "A inscrição que já existia")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "403",
            description = "Perfil incompleto ou menor de 18 anos, ou sessão web sem o token CSRF no header "
                    + "X-XSRF-TOKEN",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Evento inexistente ou rascunho",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409", description = "Evento cancelado, já começado ou lotado",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "429", description = "Limite de inscrições e cancelamentos desta conta esgotado",
            headers = @Header(name = "Retry-After", required = true,
                    description = "Segundos até a próxima chamada ficar disponível",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0",
                            maximum = AccountRateLimit.MAX_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503",
            description = "O evento está ocupado com outras inscrições, ou o limite desta conta não pôde ser contado; "
                    + "nada foi gravado",
            headers = @Header(name = "Retry-After", required = true, description = "Segundos até tentar de novo",
                    schema = @Schema(type = "integer", format = "int32",
                            minimum = EventsExceptionHandler.RETRY_AFTER_SECONDS,
                            maximum = EventsExceptionHandler.RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<RegistrationResponse> register(@Parameter(description = EVENT_ID_DESCRIPTION,
            schema = @Schema(type = "string", format = "uuid", minLength = ApiSchemas.UUID_LENGTH,
                    maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId, AccountId account) {
        rateLimit.consume(account);
        RegistrationOutcome outcome = registrations.register(eventId, account);
        var body = RegistrationResponse.of(outcome.registration());
        if (outcome.created()) {
            return ResponseEntity.created(URI.create(EventController.PATH + "/" + eventId + "/registration")).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping(PATH)
    @Operation(operationId = "getMyRegistration", summary = "Lê a própria inscrição no evento",
            description = "Só a de quem chama: não há como ler a inscrição de outra pessoa. Sem inscrição, 404, "
                    + "exista o evento ou não.")
    @ApiResponse(responseCode = "200", description = "A inscrição")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Quem chama não está inscrito nesse evento",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    RegistrationResponse find(@Parameter(description = EVENT_ID_DESCRIPTION,
            schema = @Schema(type = "string", format = "uuid", minLength = ApiSchemas.UUID_LENGTH,
                    maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId, AccountId account) {
        return RegistrationResponse.of(registrations.find(eventId, account));
    }

    @DeleteMapping(PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "cancelMyRegistration", summary = "Cancela a própria inscrição no evento",
            description = "Idempotente: sem inscrição, também responde 204. Só até o evento começar. Cada chamada "
                    + "gasta o limite da conta, compartilhado com registerForEvent: 60 por hora, repostas aos poucos.")
    @ApiResponse(responseCode = "204", description = "Quem chama não está inscrito no evento")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Evento inexistente ou rascunho",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409", description = "O evento já começou",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "429", description = "Limite de inscrições e cancelamentos desta conta esgotado",
            headers = @Header(name = "Retry-After", required = true,
                    description = "Segundos até a próxima chamada ficar disponível",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0",
                            maximum = AccountRateLimit.MAX_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503", description = "O limite desta conta não pôde ser contado; nada foi feito",
            headers = @Header(name = "Retry-After", required = true, description = "Segundos até tentar de novo",
                    schema = @Schema(type = "integer", format = "int32",
                            minimum = AccountRateLimit.UNAVAILABLE_RETRY_AFTER_SECONDS,
                            maximum = AccountRateLimit.UNAVAILABLE_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    void unregister(@Parameter(description = EVENT_ID_DESCRIPTION,
            schema = @Schema(type = "string", format = "uuid", minLength = ApiSchemas.UUID_LENGTH,
                    maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId, AccountId account) {
        rateLimit.consume(account);
        registrations.unregister(eventId, account);
    }

    /** As inscrições de quem chama em eventos que ainda não acabaram, pelo início do evento. */
    @GetMapping(MINE_PATH)
    @Operation(operationId = "listMyRegistrations", summary = "Lista as próprias inscrições",
            description = "Em eventos que ainda não acabaram, inclusive cancelados e em andamento, do início mais "
                    + "próximo ao mais distante, paginadas por cursor. A última página vem com nextPageToken null.")
    @ApiResponse(responseCode = "200", description = "Uma página das inscrições")
    @ApiResponse(responseCode = "400", description = "maxPageSize fora de 1 a 50, ou pageToken que a API não gerou",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    PageResponse<MyRegistrationResponse> mine(AccountId account,
            @Parameter(description = "Quantas inscrições no máximo nesta página",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1",
                            maximum = "" + PageSize.MAX, defaultValue = "" + PageSize.DEFAULT))
            @RequestParam(name = "maxPageSize", required = false) String maxPageSize,
            @Parameter(description = "O nextPageToken da página anterior; ausente na primeira",
                    schema = @Schema(type = "string", pattern = PageToken.PATTERN, maxLength = PageToken.MAX_LENGTH))
            @RequestParam(required = false) String pageToken) {
        int size = PageSize.of(maxPageSize);
        ResultPage<RegisteredEvent> page = pageToken == null
                ? registrations.firstOf(account, size)
                : registrations.ofAfter(account, PageToken.decode(pageToken), size);
        return PageResponse.of(page, MyRegistrationResponse::of);
    }

}
