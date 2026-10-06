package bipo.tech.duoraapi.events.application;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.domain.Event;
import bipo.tech.duoraapi.events.domain.EventStatus;

/**
 * O evento como qualquer pessoa logada o vê: sem capacidade, contagem nem inscritos. {@code status} é
 * PUBLISHED ou CANCELLED, porque rascunho não chega até aqui.
 */
public record EventView(UUID id, String title, String description, Instant startsAt, Instant endsAt,
        EventStatus status) {

    static EventView of(Event event) {
        return new EventView(event.id(), event.title().value(), event.description().value(),
                event.schedule().startsAt(), event.schedule().endsAt(), event.status());
    }

    PageCursor cursor() {
        return new PageCursor(startsAt, id);
    }

}
