package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import bipo.tech.duoraapi.events.domain.Capacity;
import bipo.tech.duoraapi.events.domain.EventDescription;
import bipo.tech.duoraapi.events.domain.EventSchedule;
import bipo.tech.duoraapi.events.domain.EventTitle;
import bipo.tech.duoraapi.events.domain.InvalidEventException;

/**
 * Corpo da criação. Só o que o ADMIN define: id, estado e contagem são do servidor, e chave desconhecida
 * é recusada com 400 (spring.jackson.deserialization.fail-on-unknown-properties). Os horários chegam como
 * texto: o Jackson aceitaria um número como segundos da época.
 */
record CreateEventRequest(String title, String description, String startsAt, String endsAt, Integer capacity) {

    EventTitle eventTitle() {
        return new EventTitle(title);
    }

    EventDescription eventDescription() {
        return new EventDescription(description);
    }

    EventSchedule schedule() {
        return new EventSchedule(parseInstant("startsAt", startsAt), parseInstant("endsAt", endsAt));
    }

    Capacity eventCapacity() {
        if (capacity == null) {
            throw new InvalidEventException("capacity is required");
        }
        return new Capacity(capacity);
    }

    /** ISO 8601 com fuso explícito (Z ou -03:00): sem ele, o mesmo texto seria um instante em cada servidor. */
    private static Instant parseInstant(String field, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidEventException(field + " must be a date and time with offset, like 2026-11-01T22:00:00Z");
        }
    }

}
