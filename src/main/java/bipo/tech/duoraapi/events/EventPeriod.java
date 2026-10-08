package bipo.tech.duoraapi.events;

import java.time.Instant;
import java.util.Objects;

/**
 * O que {@link EventCalendar} sabe do horário de um evento num instante.
 *
 * @param endsAt o fim agendado, que não muda depois da criação, nem com o cancelamento
 * @param underway publicado e dentro do horário {@code [startsAt, endsAt)} naquele instante
 */
public record EventPeriod(Instant endsAt, boolean underway) {

    public EventPeriod {
        Objects.requireNonNull(endsAt, "endsAt");
    }

}
