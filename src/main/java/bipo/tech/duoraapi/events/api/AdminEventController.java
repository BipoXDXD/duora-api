package bipo.tech.duoraapi.events.api;

import static bipo.tech.duoraapi.events.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.events.api.ApiSchemas.PROBLEM_SCHEMA;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.events.application.AdminEventView;
import bipo.tech.duoraapi.events.application.EventAdministrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Eventos para o ADMIN (docs/adr/0016). O papel é conferido pela rota /api/admin/** em
 * SecurityConfiguration. Publicar e cancelar são ações ({@code :verbo}, docs/adr/0005); não há edição.
 */
@RestController
@Tag(name = "admin-events", description = "Criação, publicação e cancelamento de eventos pelo ADMIN")
class AdminEventController {

    static final String PATH = "/api/admin/events";

    /**
     * O id sem ":": sem o filtro, GET em {@code {id}:publish} cairia na leitura com o id "uuid:publish" (400),
     * e o OPTIONS das ações anunciaria um GET que elas não têm. Com ele, a rota da ação responde 405 ao GET.
     */
    private static final String EVENT_PATH = PATH + "/{id:[^:]+}";

    private static final String EVENT_ID_DESCRIPTION = "Id do evento, como a criação o devolveu";

    private final EventAdministrationService administration;

    AdminEventController(EventAdministrationService administration) {
        this.administration = administration;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createEvent", summary = "Cria um evento em rascunho",
            description = "O rascunho só aparece para o ADMIN até ser publicado. Sem Idempotency-Key: repetir a "
                    + "criação gera outro rascunho, que o ADMIN cancela (docs/adr/0016).")
    @ApiResponse(responseCode = "201", description = "O rascunho criado",
            headers = @Header(name = "Location", required = true, description = "Endereço do evento criado",
                    schema = @Schema(type = "string", format = "uri", maxLength = ApiSchemas.LOCATION_MAX_LENGTH)))
    @ApiResponse(responseCode = "400",
            description = "Campo ausente ou inválido (texto, horário sem fuso, início no passado ou além de um ano, "
                    + "duração acima de 12 horas, capacidade fora de 2 a 200), JSON malformado ou campo desconhecido",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<AdminEventResponse> create(@RequestBody CreateEventRequest request) {
        AdminEventView event = administration.createDraft(request.eventTitle(), request.eventDescription(),
                request.schedule(), request.eventCapacity());
        return ResponseEntity.created(URI.create(PATH + "/" + event.id())).body(AdminEventResponse.of(event));
    }

    @GetMapping(EVENT_PATH)
    @Operation(operationId = "getAdminEvent", summary = "Lê um evento, inclusive rascunho",
            description = "Traz o estado guardado e quantas pessoas se inscreveram, nunca quem.")
    @ApiResponse(responseCode = "200", description = "O evento")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há evento com esse id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    AdminEventResponse find(@Parameter(description = EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
            format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID id) {
        return AdminEventResponse.of(administration.find(id));
    }

    @PostMapping(PATH + "/{id}:publish")
    @Operation(operationId = "publishEvent", summary = "Publica um rascunho",
            description = "O evento passa a aparecer para quem está logado e aceita inscrições. Só vale para "
                    + "rascunho que ainda não começou; repetir responde 409 sem mudar nada.")
    @ApiResponse(responseCode = "200", description = "O evento publicado")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há evento com esse id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409",
            description = "O evento não é rascunho, já começou ou mudou ao mesmo tempo por outra ação",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    AdminEventResponse publish(@Parameter(description = EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
            format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID id) {
        return AdminEventResponse.of(administration.publish(id));
    }

    @PostMapping(PATH + "/{id}:cancel")
    @Operation(operationId = "cancelEvent", summary = "Cancela um evento",
            description = "Vale para rascunho ou publicado que ainda não acabou, inclusive em andamento. O "
                    + "evento continua visível, para quem se inscreveu saber, e não aceita inscrições. "
                    + "Repetir responde 409 sem mudar nada.")
    @ApiResponse(responseCode = "200", description = "O evento cancelado")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há evento com esse id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409",
            description = "O evento já foi cancelado, já acabou ou mudou ao mesmo tempo por outra ação",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    AdminEventResponse cancel(@Parameter(description = EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
            format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID id) {
        return AdminEventResponse.of(administration.cancel(id));
    }

}
