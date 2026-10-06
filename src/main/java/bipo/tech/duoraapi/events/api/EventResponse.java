package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.EventView;

/**
 * O evento para quem está logado. Sem capacidade, contagem nem inscritos: quem vai a um encontro não
 * aparece para os outros (docs/adr/0016). {@code status}: PUBLISHED ou CANCELLED.
 */
record EventResponse(UUID id, String title, String description, Instant startsAt, Instant endsAt, String status) {

    static EventResponse of(EventView event) {
        return new EventResponse(event.id(), event.title(), event.description(), event.startsAt(), event.endsAt(),
                event.status().name());
    }

}
