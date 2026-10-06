package bipo.tech.duoraapi.events.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.events.application.EventCatalogService;
import bipo.tech.duoraapi.events.application.ResultPage;
import bipo.tech.duoraapi.events.application.EventView;

/** Eventos para quem está logado (docs/adr/0016): a lista dos que ainda vão começar e cada evento visível. */
@RestController
class EventController {

    static final String PATH = "/api/events";

    private final EventCatalogService catalog;

    EventController(EventCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping(PATH)
    PageResponse<EventResponse> upcoming(@RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String pageToken) {
        int size = PageSize.of(pageSize);
        ResultPage<EventView> page = pageToken == null
                ? catalog.firstUpcoming(size)
                : catalog.upcomingAfter(PageToken.decode(pageToken), size);
        return PageResponse.of(page, EventResponse::of);
    }

    @GetMapping(PATH + "/{id}")
    EventResponse find(@PathVariable UUID id) {
        return EventResponse.of(catalog.find(id));
    }

}
