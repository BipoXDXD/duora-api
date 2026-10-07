package bipo.tech.duoraapi.events;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.events.domain.EventRepository;
import bipo.tech.duoraapi.events.domain.RegistrationRepository;

/**
 * API publicada do módulo events para o pareamento (docs/adr/0017): quem está inscrito num evento em
 * andamento, sem que outro módulo leia as tabelas daqui.
 *
 * <p>Com o evento em andamento a lista não muda mais: a inscrição e a saída só valem até o início
 * (docs/adr/0016). Por isso a leitura não trava o evento.
 */
@Service
public class EventRoster {

    private final EventRepository events;
    private final RegistrationRepository registrations;

    public EventRoster(EventRepository events, RegistrationRepository registrations) {
        this.events = events;
        this.registrations = registrations;
    }

    @Transactional(readOnly = true)
    public Roster rosterOf(UUID eventId, Instant now) {
        return events.findById(eventId)
                .<Roster>map(event -> event.isUnderway(now)
                        ? new Roster.Underway(registrations.findAccountsByEvent(eventId))
                        : new Roster.NotUnderway())
                .orElseGet(Roster.UnknownEvent::new);
    }

}
