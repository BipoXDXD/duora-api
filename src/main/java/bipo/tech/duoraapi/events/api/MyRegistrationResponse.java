package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.domain.RegisteredEvent;

/**
 * Item de "as minhas inscrições": a referência ao evento e o resumo mínimo para a tela, sem outra
 * requisição por item (docs/adr/0005). {@code eventStatus}: PUBLISHED ou CANCELLED.
 */
record MyRegistrationResponse(UUID eventId, String title, Instant startsAt, Instant endsAt, String eventStatus,
        Instant registeredAt) {

    static MyRegistrationResponse of(RegisteredEvent registration) {
        return new MyRegistrationResponse(registration.eventId(), registration.title(), registration.startsAt(),
                registration.endsAt(), registration.eventStatus().name(), registration.registeredAt());
    }

}
