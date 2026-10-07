package bipo.tech.duoraapi.events.application;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.domain.Event;
import bipo.tech.duoraapi.events.domain.EventStatus;

/** O evento como o ADMIN o vê: inclusive rascunho e quantas pessoas se inscreveram, mas nunca quem. */
public record AdminEventView(UUID id, String title, String description, Instant startsAt, Instant endsAt,
        int capacity, EventStatus status, long registrationCount) {

    static AdminEventView of(Event event, long registrationCount) {
        return new AdminEventView(event.id(), event.title().value(), event.description().value(),
                event.schedule().startsAt(), event.schedule().endsAt(), event.capacity().places(), event.status(),
                registrationCount);
    }

}
