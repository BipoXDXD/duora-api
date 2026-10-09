package bipo.tech.duoraapi.events.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.events.domain.Capacity;
import bipo.tech.duoraapi.events.domain.Event;
import bipo.tech.duoraapi.events.domain.EventDescription;
import bipo.tech.duoraapi.events.domain.EventRepository;
import bipo.tech.duoraapi.events.domain.EventSchedule;
import bipo.tech.duoraapi.events.domain.EventStatus;
import bipo.tech.duoraapi.events.domain.EventTitle;
import bipo.tech.duoraapi.events.domain.RegistrationRepository;

/** O que o ADMIN faz com eventos. O papel é conferido na rota (/api/admin/**), antes de chegar aqui. */
@Service
public class EventAdministrationService {

    /** Depois de qualquer evento possível na ordem decrescente: nenhum início passa de 9999 (docs/adr/0016). */
    private static final PageCursor AFTER_ANY_EVENT =
            new PageCursor(Instant.parse("9999-12-31T23:59:59.999999Z"), new UUID(-1L, -1L));

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

    /** A primeira página dos eventos nos estados pedidos, do início mais distante ao mais antigo. */
    @Transactional(readOnly = true)
    public ResultPage<AdminEventView> list(Set<EventStatus> statuses, int pageSize) {
        return list(statuses, AFTER_ANY_EVENT, pageSize);
    }

    /** A página seguinte à que terminou em {@code after}. */
    @Transactional(readOnly = true)
    public ResultPage<AdminEventView> listAfter(Set<EventStatus> statuses, PageCursor after, int pageSize) {
        return list(statuses, after, pageSize);
    }

    private ResultPage<AdminEventView> list(Set<EventStatus> statuses, PageCursor after, int pageSize) {
        List<Event> fetched = events.findForAdministration(statuses, after.startsAt(), after.eventId(),
                Limit.of(pageSize + 1));
        Map<UUID, Long> counts = registrations.countByEvents(fetched.stream().map(Event::id).toList());
        List<AdminEventView> views = fetched.stream()
                .map(event -> AdminEventView.of(event, counts.getOrDefault(event.id(), 0L)))
                .toList();
        return ResultPage.fromOneMoreThan(pageSize, views, AdminEventView::cursor);
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
