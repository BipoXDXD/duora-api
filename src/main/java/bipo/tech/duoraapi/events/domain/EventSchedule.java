package bipo.tech.duoraapi.events.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Quando o evento acontece, como intervalo semiaberto [startsAt, endsAt): começou no instante de
 * início e já acabou no instante de fim. Os instantes ficam em microssegundos, a precisão do
 * timestamptz, para a resposta da criação ser igual à de uma leitura posterior.
 */
public record EventSchedule(Instant startsAt, Instant endsAt) {

    public static final Duration MAX_DURATION = Duration.ofHours(12);

    public EventSchedule {
        if (startsAt == null) {
            throw new InvalidEventException("startsAt is required");
        }
        if (endsAt == null) {
            throw new InvalidEventException("endsAt is required");
        }
        startsAt = startsAt.truncatedTo(ChronoUnit.MICROS);
        endsAt = endsAt.truncatedTo(ChronoUnit.MICROS);
        if (!endsAt.isAfter(startsAt)) {
            throw new InvalidEventException("endsAt must be after startsAt");
        }
        if (Duration.between(startsAt, endsAt).compareTo(MAX_DURATION) > 0) {
            throw new InvalidEventException("an event lasts at most " + MAX_DURATION.toHours() + " hours");
        }
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startsAt);
    }

    public boolean hasEnded(Instant now) {
        return !now.isBefore(endsAt);
    }

}
