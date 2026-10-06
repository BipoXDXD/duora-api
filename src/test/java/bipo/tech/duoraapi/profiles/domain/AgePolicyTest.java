package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class AgePolicyTest {

    /** Meio-dia em Rio Branco (UTC-5). */
    private static final Instant NOON_IN_ACRE_ON_2026_10_05 = Instant.parse("2026-10-05T17:00:00Z");

    @Test
    void isAdultOnTheEighteenthBirthday() {
        assertThat(AgePolicy.isAdult(LocalDate.parse("2008-10-05"), NOON_IN_ACRE_ON_2026_10_05)).isTrue();
    }

    @Test
    void isNotAdultTheDayBeforeTheEighteenthBirthday() {
        assertThat(AgePolicy.isAdult(LocalDate.parse("2008-10-06"), NOON_IN_ACRE_ON_2026_10_05)).isFalse();
    }

    @Test
    void isAdultLongAfterTheEighteenthBirthday() {
        assertThat(AgePolicy.isAdult(LocalDate.parse("1990-05-10"), NOON_IN_ACRE_ON_2026_10_05)).isTrue();
    }

    /**
     * O dia vale no fuso mais a oeste do Brasil: às 03:00 UTC ainda é 4 de outubro no Acre (e em
     * Brasília), então quem faz 18 anos em 5 de outubro ainda não é maior de idade.
     */
    @Test
    void usesTheDateOfTheWesternmostBrazilianTimeZone() {
        var earlyOnOctoberFifthInUtc = Instant.parse("2026-10-05T03:00:00Z");

        assertThat(AgePolicy.isAdult(LocalDate.parse("2008-10-05"), earlyOnOctoberFifthInUtc)).isFalse();
    }

    @Test
    void becomesAdultAtMidnightInAcre() {
        var midnightInAcre = Instant.parse("2026-10-05T05:00:00Z");

        assertThat(AgePolicy.isAdult(LocalDate.parse("2008-10-05"), midnightInAcre)).isTrue();
    }

    /**
     * Nascido em 29 de fevereiro completa anos em 1º de março nos anos não bissextos (Código Civil,
     * art. 132, § 3º: sem dia correspondente, o prazo vence no dia seguinte).
     */
    @Test
    void leapDayBirthIsNotAdultOnFebruaryTwentyEighth() {
        assertThat(AgePolicy.isAdult(LocalDate.parse("2008-02-29"), Instant.parse("2026-02-28T17:00:00Z"))).isFalse();
    }

    @Test
    void leapDayBirthIsAdultOnMarchFirst() {
        assertThat(AgePolicy.isAdult(LocalDate.parse("2008-02-29"), Instant.parse("2026-03-01T17:00:00Z"))).isTrue();
    }

    @Test
    void birthDateInTheFutureIsNotAdult() {
        assertThat(AgePolicy.isAdult(LocalDate.parse("2030-01-01"), NOON_IN_ACRE_ON_2026_10_05)).isFalse();
    }

    /** Idade em anos completos: na véspera dos 121, ainda são 120. */
    @Test
    void dayBeforeTurningOneHundredAndTwentyOneIsPlausible() {
        assertThat(AgePolicy.isPlausible(LocalDate.parse("1905-10-06"), NOON_IN_ACRE_ON_2026_10_05)).isTrue();
    }

    @Test
    void oneHundredAndTwentyOneYearsIsNotPlausible() {
        assertThat(AgePolicy.isPlausible(LocalDate.parse("1905-10-05"), NOON_IN_ACRE_ON_2026_10_05)).isFalse();
    }

}
