package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.time.api.constraints.DateRange;

/** A idade só cresce com o tempo: nenhuma data, fuso ou ano bissexto faz alguém deixar de ser adulto. */
class AgePolicyPropertiesTest {

    private static final String OLDEST_BIRTH = "1870-01-01";
    private static final String NEWEST_BIRTH = "2040-12-31";
    /** 2000-01-01T00:00:00Z, em segundos desde a epoch. */
    private static final long FIRST_NOW = 946_684_800L;
    /** 2100-12-31T23:59:59Z, em segundos desde a epoch. */
    private static final long LAST_NOW = 4_133_980_799L;
    /** 130 anos de 365,25 dias, em segundos: atravessa a maioridade e o teto de 120 anos. */
    private static final long LONGEST_WAIT = 4_102_488_000L;

    @Property
    void onceAnAdultAlwaysAnAdult(@ForAll @DateRange(min = OLDEST_BIRTH, max = NEWEST_BIRTH) LocalDate birthDate,
            @ForAll @LongRange(min = FIRST_NOW, max = LAST_NOW) long nowSeconds,
            @ForAll @LongRange(max = LONGEST_WAIT) long elapsedSeconds) {
        Instant now = Instant.ofEpochSecond(nowSeconds);
        Instant later = now.plusSeconds(elapsedSeconds);
        Assume.that(AgePolicy.isAdult(birthDate, now));

        assertThat(AgePolicy.isAdult(birthDate, later)).isTrue();
    }

    @Property
    void onceImplausibleAlwaysImplausible(@ForAll @DateRange(min = OLDEST_BIRTH, max = NEWEST_BIRTH) LocalDate birthDate,
            @ForAll @LongRange(min = FIRST_NOW, max = LAST_NOW) long nowSeconds,
            @ForAll @LongRange(max = LONGEST_WAIT) long elapsedSeconds) {
        Instant now = Instant.ofEpochSecond(nowSeconds);
        Instant later = now.plusSeconds(elapsedSeconds);
        Assume.that(!AgePolicy.isPlausible(birthDate, now));

        assertThat(AgePolicy.isPlausible(birthDate, later)).isFalse();
    }

}
