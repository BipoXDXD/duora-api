package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.AdminEventView;

/**
 * O evento para o ADMIN. {@code status}: DRAFT, PUBLISHED ou CANCELLED; em andamento e encerrado se leem
 * de startsAt e endsAt. {@code registrationCount} é só a contagem: a lista de inscritos não sai da API.
 */
record AdminEventResponse(UUID id, String title, String description, Instant startsAt, Instant endsAt, int capacity,
        String status, long registrationCount) {

    static AdminEventResponse of(AdminEventView event) {
        return new AdminEventResponse(event.id(), event.title(), event.description(), event.startsAt(),
                event.endsAt(), event.capacity(), event.status().name(), event.registrationCount());
    }

}
