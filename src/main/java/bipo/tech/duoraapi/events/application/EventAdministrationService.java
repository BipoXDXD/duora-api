package bipo.tech.duoraapi.events.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.events.domain.Capacity;
import bipo.tech.duoraapi.events.domain.Event;
import bipo.tech.duoraapi.events.domain.EventDescription;
import bipo.tech.duoraapi.events.domain.EventRepository;
import bipo.tech.duoraapi.events.domain.EventSchedule;
import bipo.tech.duoraapi.events.domain.EventTitle;
import bipo.tech.duoraapi.events.domain.RegistrationRepository;

/** O que o ADMIN faz com eventos. O papel é conferido na rota (/api/admin/**), antes de chegar aqui. */
@Service
public class EventAdministrationService {

    private final EventRepository events;
    private final RegistrationRepository registrations;
    private final Clock clock;

    public EventAdministrationService(EventRepository events, RegistrationRepository registrations, Clock clock) {
        this.events = events;
        this.registrations = registrations;
        this.clock = clock;
    }

    @Transactional
    public AdminEventView createDraft(EventTitle title, EventDescription description, EventSchedule schedule,
            Capacity capacity) {
        Event event = events.save(Event.draft(title, description, schedule, capacity, clock.instant()));
        return AdminEventView.of(event, 0);
    }

    @Transactional(readOnly = true)
    public AdminEventView find(UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(EventNotFoundException::new);
        return AdminEventView.of(event, registrations.countByEvent(eventId));
    }

    /** @throws bipo.tech.duoraapi.events.domain.EventStateConflictException se não for um rascunho por começar */
    @Transactional
    public AdminEventView publish(UUID eventId) {
        return change(eventId, Event::publish);
    }

    /** @throws bipo.tech.duoraapi.events.domain.EventStateConflictException se já foi cancelado ou já acabou */
    @Transactional
    public AdminEventView cancel(UUID eventId) {
        return change(eventId, Event::cancel);
    }

    private AdminEventView change(UUID eventId, BiConsumer<Event, Instant> transition) {
        Event event = events.findById(eventId).orElseThrow(EventNotFoundException::new);
        transition.accept(event, clock.instant());
        events.flush();
        return AdminEventView.of(event, registrations.countByEvent(eventId));
    }

}
