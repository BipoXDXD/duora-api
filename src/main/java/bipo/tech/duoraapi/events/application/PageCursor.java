package bipo.tech.duoraapi.events.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Onde a página anterior parou, na ordem das listas de eventos: início do evento e, no empate, o id.
 * A próxima página começa logo depois desse par (paginação por keyset, sem OFFSET).
 */
public record PageCursor(Instant startsAt, UUID eventId) {

    public PageCursor {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(eventId, "eventId");
    }

}
