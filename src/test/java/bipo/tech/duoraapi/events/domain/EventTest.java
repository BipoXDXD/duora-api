package bipo.tech.duoraapi.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class EventTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Instant STARTS_AT = Instant.parse("2026-11-01T22:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-11-02T01:00:00Z");
    private static final Instant JUST_BEFORE_START = Instant.parse("2026-11-01T21:59:59.999999Z");
    private static final Instant JUST_BEFORE_END = Instant.parse("2026-11-02T00:59:59.999999Z");
    private static final int CAPACITY = 10;

    @Test
    void newEventIsADraft() {
        var event = draftStartingAt(STARTS_AT);

        assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.title()).isEqualTo(new EventTitle("Noite de jogos"));
        assertThat(event.description()).isEqualTo(new EventDescription("Jogos de tabuleiro em dupla."));
        assertThat(event.schedule()).isEqualTo(new EventSchedule(STARTS_AT, ENDS_AT));
        assertThat(event.capacity()).isEqualTo(new Capacity(CAPACITY));
        assertThat(event.createdAt()).isEqualTo(NOW);
    }

    @Test
    void draftIsNotVisibleToParticipants() {
        assertThat(draftStartingAt(STARTS_AT).isVisibleToParticipants()).isFalse();
    }

    @Test
    void eventCannotStartNow() {
        assertThatThrownBy(() -> draftStartingAt(NOW))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("startsAt must be in the future");
    }

    @Test
    void eventCanStartRightAfterNow() {
        var event = draftStartingAt(Instant.parse("2026-10-06T12:00:00.000001Z"));

        assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void eventCanStartUpToAYearAhead() {
        var event = draftStartingAt(Instant.parse("2027-10-06T12:00:00Z"));

        assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void eventCannotStartMoreThanAYearAhead() {
        assertThatThrownBy(() -> draftStartingAt(Instant.parse("2027-10-06T12:00:00.000001Z")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("startsAt must be at most 365 days ahead");
    }

    @Test
    void publishingADraftMakesItVisible() {
        var event = draftStartingAt(STARTS_AT);

        event.publish(NOW);

        assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(event.isVisibleToParticipants()).isTrue();
    }

    @Test
    void publishedEventCannotBePublishedAgain() {
        var event = published();

        assertThatThrownBy(() -> event.publish(NOW))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("only a draft can be published");
        assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void cancelledEventCannotBePublished() {
        var event = draftStartingAt(STARTS_AT);
        event.cancel(NOW);

        assertThatThrownBy(() -> event.publish(NOW))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("only a draft can be published");
        assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    void draftCanBePublishedJustBeforeItStarts() {
        var event = draftStartingAt(STARTS_AT);

        event.publish(JUST_BEFORE_START);

        assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void draftCannotBePublishedOnceItsStartHasPassed() {
        var event = draftStartingAt(STARTS_AT);

        assertThatThrownBy(() -> event.publish(STARTS_AT))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event has already started");
        assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void draftCanBeCancelled() {
        var event = draftStartingAt(STARTS_AT);

        event.cancel(NOW);

        assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    void publishedEventCanBeCancelledWhileItRuns() {
        var event = published();

        event.cancel(JUST_BEFORE_END);

        assertThat(event.status()).isEqualTo(EventStatus.CANCELLED);
        assertThat(event.isVisibleToParticipants()).isTrue();
    }

    @Test
    void endedEventCannotBeCancelled() {
        var event = published();

        assertThatThrownBy(() -> event.cancel(ENDS_AT))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event has already ended");
        assertThat(event.status()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void cancelledEventCannotBeCancelledAgain() {
        var event = published();
        event.cancel(NOW);

        assertThatThrownBy(() -> event.cancel(NOW))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event is already cancelled");
    }

    @Test
    void publishedEventWithPlacesLeftAcceptsRegistrationJustBeforeItStarts() {
        assertThatCode(() -> published().ensureAcceptsRegistration(CAPACITY - 1, JUST_BEFORE_START))
                .doesNotThrowAnyException();
    }

    @Test
    void fullEventRefusesRegistration() {
        assertThatThrownBy(() -> published().ensureAcceptsRegistration(CAPACITY, NOW))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event is full");
    }

    @Test
    void startedEventRefusesRegistration() {
        assertThatThrownBy(() -> published().ensureAcceptsRegistration(0, STARTS_AT))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event has already started");
    }

    @Test
    void cancelledEventRefusesRegistration() {
        var event = published();
        event.cancel(NOW);

        assertThatThrownBy(() -> event.ensureAcceptsRegistration(0, NOW))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event was cancelled");
    }

    @Test
    void draftRefusesRegistration() {
        assertThatThrownBy(() -> draftStartingAt(STARTS_AT).ensureAcceptsRegistration(0, NOW))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event is not published");
    }

    @Test
    void registrationCanBeCancelledJustBeforeTheEventStarts() {
        assertThatCode(() -> published().ensureAllowsLeaving(JUST_BEFORE_START)).doesNotThrowAnyException();
    }

    @Test
    void registrationCannotBeCancelledOnceTheEventStarted() {
        assertThatThrownBy(() -> published().ensureAllowsLeaving(STARTS_AT))
                .isInstanceOf(EventStateConflictException.class)
                .hasMessage("the event has already started");
    }

    /** Sair de um evento cancelado não muda nada para ninguém, então é permitido até o início. */
    @Test
    void registrationOfACancelledEventCanBeCancelled() {
        var event = published();
        event.cancel(NOW);

        assertThatCode(() -> event.ensureAllowsLeaving(NOW)).doesNotThrowAnyException();
    }

    @Test
    void publishedEventIsUnderwayFromTheStartInstant() {
        assertThat(published().isUnderway(STARTS_AT)).isTrue();
    }

    @Test
    void publishedEventIsNotUnderwayJustBeforeTheStart() {
        assertThat(published().isUnderway(JUST_BEFORE_START)).isFalse();
    }

    @Test
    void publishedEventIsStillUnderwayJustBeforeTheEnd() {
        assertThat(published().isUnderway(JUST_BEFORE_END)).isTrue();
    }

    @Test
    void publishedEventIsNoLongerUnderwayAtTheEndInstant() {
        assertThat(published().isUnderway(ENDS_AT)).isFalse();
    }

    @Test
    void cancelledEventIsNotUnderwayDuringItsSchedule() {
        var event = published();
        event.cancel(NOW);

        assertThat(event.isUnderway(STARTS_AT)).isFalse();
    }

    @Test
    void draftIsNotUnderwayDuringItsSchedule() {
        assertThat(draftStartingAt(STARTS_AT).isUnderway(STARTS_AT)).isFalse();
    }

    private static Event published() {
        var event = draftStartingAt(STARTS_AT);
        event.publish(NOW);
        return event;
    }

    private static Event draftStartingAt(Instant startsAt) {
        return Event.draft(new EventTitle("Noite de jogos"), new EventDescription("Jogos de tabuleiro em dupla."),
                new EventSchedule(startsAt, startsAt.plusSeconds(3 * 3600)), new Capacity(CAPACITY), NOW);
    }

}
