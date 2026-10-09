package bipo.tech.duoraapi.events.api;

import static bipo.tech.duoraapi.events.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.events.api.ApiSchemas.PROBLEM_SCHEMA;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.events.application.EventCatalogService;
import bipo.tech.duoraapi.events.application.EventView;
import bipo.tech.duoraapi.events.application.ResultPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Eventos para quem está logado (docs/adr/0016): a lista dos que ainda vão começar e cada evento visível. */
@RestController
@Tag(name = "events", description = "Eventos publicados e as inscrições de quem está logado")
class EventController {

    static final String PATH = "/api/events";

    private final EventCatalogService catalog;

    EventController(EventCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping(PATH)
    @Operation(operationId = "listUpcomingEvents", summary = "Lista os eventos que ainda vão começar",
            description = "Só os publicados, do início mais próximo ao mais distante, paginados por cursor. A "
                    + "última página vem com nextPageToken null.")
    @ApiResponse(responseCode = "200", description = "Uma página dos eventos")
    @ApiResponse(responseCode = "400", description = "maxPageSize fora de 1 a " + PageSize.MAX + ", ou pageToken que a API não gerou",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    PageResponse<EventResponse> upcoming(
            @Parameter(description = "Quantos eventos no máximo nesta página",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1",
                            maximum = "" + PageSize.MAX, defaultValue = "" + PageSize.DEFAULT))
            @RequestParam(name = "maxPageSize", required = false) String maxPageSize,
            @Parameter(description = "O nextPageToken da página anterior; ausente na primeira",
                    schema = @Schema(type = "string", pattern = PageToken.PATTERN, maxLength = PageToken.MAX_LENGTH))
            @RequestParam(required = false) String pageToken) {
        int size = PageSize.of(maxPageSize);
        ResultPage<EventView> page = pageToken == null
                ? catalog.firstUpcoming(size)
                : catalog.upcomingAfter(PageToken.decode(pageToken), size);
        return PageResponse.of(page, EventResponse::of);
    }

    @GetMapping(PATH + "/{id}")
    @Operation(operationId = "getEvent", summary = "Lê um evento publicado ou cancelado",
            description = "Sem capacidade, contagem nem inscritos. Um rascunho responde como um id que não existe.")
    @ApiResponse(responseCode = "200", description = "O evento")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Evento inexistente ou rascunho",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    EventResponse find(@Parameter(description = "Id do evento", schema = @Schema(type = "string", format = "uuid",
            minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID id) {
        return EventResponse.of(catalog.find(id));
    }

}
