package bipo.tech.duoraapi.events.api;

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

/**
 * Eventos para o ADMIN (docs/adr/0016). O papel é conferido pela rota /api/admin/** em
 * SecurityConfiguration. Publicar e cancelar são ações ({@code :verbo}, docs/adr/0005); não há edição.
 */
@RestController
class AdminEventController {

    static final String PATH = "/api/admin/events";

    /**
     * O id sem ":": sem o filtro, GET em {@code {id}:publish} cairia na leitura com o id "uuid:publish" (400),
     * e o OPTIONS das ações anunciaria um GET que elas não têm. Com ele, a rota da ação responde 405 ao GET.
     */
    private static final String EVENT_PATH = PATH + "/{id:[^:]+}";

    private final EventAdministrationService administration;

    AdminEventController(EventAdministrationService administration) {
        this.administration = administration;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdminEventResponse> create(@RequestBody CreateEventRequest request) {
        AdminEventView event = administration.createDraft(request.eventTitle(), request.eventDescription(),
                request.schedule(), request.eventCapacity());
        return ResponseEntity.created(URI.create(PATH + "/" + event.id())).body(AdminEventResponse.of(event));
    }

    @GetMapping(EVENT_PATH)
    AdminEventResponse find(@PathVariable UUID id) {
        return AdminEventResponse.of(administration.find(id));
    }

    @PostMapping(PATH + "/{id}:publish")
    AdminEventResponse publish(@PathVariable UUID id) {
        return AdminEventResponse.of(administration.publish(id));
    }

    @PostMapping(PATH + "/{id}:cancel")
    AdminEventResponse cancel(@PathVariable UUID id) {
        return AdminEventResponse.of(administration.cancel(id));
    }

}
