package bipo.tech.duoraapi.events;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.events.domain.EventRepository;

/**
 * API publicada do módulo events para o chat (docs/adr/0021): se o evento está em andamento e quando ele
 * termina, sem que outro módulo leia as tabelas daqui e sem carregar a lista de inscritos, como
 * {@link EventRoster} faria.
 */
@Service
public class EventCalendar {

    private final EventRepository events;

    EventCalendar(EventRepository events) {
        this.events = events;
    }

    /** O horário do evento visto em {@code now}; vazio se não há evento com esse id. */
    @Transactional(readOnly = true)
    public Optional<EventPeriod> periodOf(UUID eventId, Instant now) {
        return events.findById(eventId)
                .map(event -> new EventPeriod(event.schedule().endsAt(), event.isUnderway(now)));
    }

}
