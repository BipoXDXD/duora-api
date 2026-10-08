package bipo.tech.duoraapi.events.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.events.RoundProgress;
import bipo.tech.duoraapi.events.domain.Event;
import bipo.tech.duoraapi.events.domain.EventRepository;
import bipo.tech.duoraapi.events.domain.EventStatus;

/** Os eventos que qualquer pessoa logada vê. Rascunho não existe fora da administração. */
@Service
public class EventCatalogService {

    /** O menor UUID: a primeira página começa antes de qualquer id. */
    private static final UUID BEFORE_ANY_ID = new UUID(0, 0);

    private final EventRepository events;
    private final RoundProgress rounds;
    private final Clock clock;

    public EventCatalogService(EventRepository events, RoundProgress rounds, Clock clock) {
        this.events = events;
        this.rounds = rounds;
        this.clock = clock;
    }

    /** A primeira página dos publicados ainda por começar, do mais próximo ao mais distante. */
    @Transactional(readOnly = true)
    public ResultPage<EventView> firstUpcoming(int pageSize) {
        Instant now = clock.instant();
        return upcoming(now, new PageCursor(now, BEFORE_ANY_ID), pageSize);
    }

    /** A página seguinte à que terminou em {@code after}. */
    @Transactional(readOnly = true)
    public ResultPage<EventView> upcomingAfter(PageCursor after, int pageSize) {
        return upcoming(clock.instant(), after, pageSize);
    }

    private ResultPage<EventView> upcoming(Instant now, PageCursor after, int pageSize) {
        var fetched = events.findUpcoming(EventStatus.PUBLISHED, now, after.startsAt(), after.eventId(),
                        Limit.of(pageSize + 1))
                .stream()
                .map(EventView::upcoming)
                .toList();
        return ResultPage.fromOneMoreThan(pageSize, fetched, EventView::cursor);
    }

    /**
     * O evento com a rodada atual, que vem do matching (docs/adr/0017).
     *
     * @throws EventNotFoundException se não existe ou é rascunho, com a mesma resposta nos dois casos
     */
    @Transactional(readOnly = true)
    public EventView find(UUID eventId) {
        Event event = events.findById(eventId)
                .filter(Event::isVisibleToParticipants)
                .orElseThrow(EventNotFoundException::new);
        return EventView.of(event, rounds.latestStartedRoundOf(eventId));
    }

}
