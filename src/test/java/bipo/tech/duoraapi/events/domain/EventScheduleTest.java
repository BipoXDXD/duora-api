package bipo.tech.duoraapi.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Intervalo semiaberto [início, fim): o evento começa no instante de início e já acabou no instante de fim. */
class EventScheduleTest {

    private static final Instant STARTS_AT = Instant.parse("2026-11-01T22:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-11-02T01:00:00Z");
    private static final EventSchedule SCHEDULE = new EventSchedule(STARTS_AT, ENDS_AT);

    @Test
    void hasNotStartedJustBeforeTheStart() {
        assertThat(SCHEDULE.hasStarted(Instant.parse("2026-11-01T21:59:59.999999Z"))).isFalse();
    }

    @Test
    void hasStartedAtTheStart() {
        assertThat(SCHEDULE.hasStarted(STARTS_AT)).isTrue();
    }

    @Test
    void hasNotEndedJustBeforeTheEnd() {
        assertThat(SCHEDULE.hasEnded(Instant.parse("2026-11-02T00:59:59.999999Z"))).isFalse();
    }

    @Test
    void hasEndedAtTheEnd() {
        assertThat(SCHEDULE.hasEnded(ENDS_AT)).isTrue();
    }

    @Test
    void acceptsTwelveHours() {
        var endsAt = Instant.parse("2026-11-02T10:00:00Z");

        assertThat(new EventSchedule(STARTS_AT, endsAt).endsAt()).isEqualTo(endsAt);
    }

    @Test
    void rejectsMoreThanTwelveHours() {
        var endsAt = Instant.parse("2026-11-02T10:00:00.000001Z");

        assertThatThrownBy(() -> new EventSchedule(STARTS_AT, endsAt))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("an event lasts at most 12 hours");
    }

    @Test
    void rejectsEndAtTheStart() {
        assertThatThrownBy(() -> new EventSchedule(STARTS_AT, STARTS_AT))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("endsAt must be after startsAt");
    }

    @Test
    void rejectsEndBeforeTheStart() {
        assertThatThrownBy(() -> new EventSchedule(ENDS_AT, STARTS_AT))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("endsAt must be after startsAt");
    }

    @Test
    void rejectsMissingStart() {
        assertThatThrownBy(() -> new EventSchedule(null, ENDS_AT))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("startsAt is required");
    }

    @Test
    void rejectsMissingEnd() {
        assertThatThrownBy(() -> new EventSchedule(STARTS_AT, null))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("endsAt is required");
    }

    /** O PostgreSQL guarda microssegundos: a resposta da criação é igual à de uma leitura posterior. */
    @Test
    void keepsMicrosecondPrecisionLikeTheDatabase() {
        var schedule = new EventSchedule(Instant.parse("2026-11-01T22:00:00.123456789Z"), ENDS_AT);

        assertThat(schedule.startsAt()).isEqualTo(Instant.parse("2026-11-01T22:00:00.123456Z"));
    }

}
