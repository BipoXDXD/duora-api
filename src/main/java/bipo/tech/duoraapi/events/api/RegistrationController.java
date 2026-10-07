package bipo.tech.duoraapi.events.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.events.application.RegistrationOutcome;
import bipo.tech.duoraapi.events.application.RegistrationService;
import bipo.tech.duoraapi.events.application.ResultPage;
import bipo.tech.duoraapi.events.domain.RegisteredEvent;
import bipo.tech.duoraapi.identity.AccountId;

/**
 * A inscrição de quem chama, como sub-recurso singular do evento (docs/adr/0016): não há id de
 * inscrição na URL, então não há como apontar para a de outra pessoa. PUT cria ou devolve a mesma
 * (idempotente pela chave evento + conta); DELETE é idempotente, 204 também na repetição.
 */
@RestController
class RegistrationController {

    static final String PATH = EventController.PATH + "/{eventId}/registration";
    static final String MINE_PATH = "/api/me/registrations";

    private final RegistrationService registrations;

    RegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    /** 201 com Location na primeira vez; 200 com a mesma inscrição nas repetições. */
    @PutMapping(PATH)
    ResponseEntity<RegistrationResponse> register(@PathVariable UUID eventId, AccountId account) {
        RegistrationOutcome outcome = registrations.register(eventId, account);
        var body = RegistrationResponse.of(outcome.registration());
        if (outcome.created()) {
            return ResponseEntity.created(URI.create(EventController.PATH + "/" + eventId + "/registration")).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping(PATH)
    RegistrationResponse find(@PathVariable UUID eventId, AccountId account) {
        return RegistrationResponse.of(registrations.find(eventId, account));
    }

    @DeleteMapping(PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@PathVariable UUID eventId, AccountId account) {
        registrations.unregister(eventId, account);
    }

    /** As inscrições de quem chama em eventos que ainda não acabaram, pelo início do evento. */
    @GetMapping(MINE_PATH)
    PageResponse<MyRegistrationResponse> mine(AccountId account, @RequestParam(name = "maxPageSize", required = false) String maxPageSize,
            @RequestParam(required = false) String pageToken) {
        int size = PageSize.of(maxPageSize);
        ResultPage<RegisteredEvent> page = pageToken == null
                ? registrations.firstOf(account, size)
                : registrations.ofAfter(account, PageToken.decode(pageToken), size);
        return PageResponse.of(page, MyRegistrationResponse::of);
    }

}
