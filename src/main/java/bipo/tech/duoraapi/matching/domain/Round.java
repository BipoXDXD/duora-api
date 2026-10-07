package bipo.tech.duoraapi.matching.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma rodada de pareamento de um evento (docs/adr/0017). A semente fica guardada para o sorteio poder ser
 * reproduzido numa investigação.
 */
public record Round(UUID eventId, RoundNumber number, long seed, Instant startedAt) {

    public Round {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(startedAt, "startedAt");
    }

}
