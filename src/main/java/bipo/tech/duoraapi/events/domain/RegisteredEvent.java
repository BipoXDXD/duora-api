package bipo.tech.duoraapi.events.domain;

import java.time.Instant;
import java.util.UUID;

/** Uma inscrição da própria pessoa com o resumo do evento, montado na mesma consulta (docs/adr/0005). */
public record RegisteredEvent(UUID eventId, String title, Instant startsAt, Instant endsAt, EventStatus eventStatus,
        Instant registeredAt) {
}
