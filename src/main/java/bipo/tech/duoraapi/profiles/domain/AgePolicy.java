package bipo.tech.duoraapi.profiles.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

/**
 * Idade a partir da data de nascimento declarada. A elegibilidade é sempre calculada na hora, com o
 * instante recebido de quem chama, e nunca guardada: um booleano gravado não envelhece.
 */
public final class AgePolicy {

    /** Maioridade civil no Brasil (Código Civil, art. 5º). */
    public static final int ADULT_AGE = 18;

    /** Acima disso, a data é erro de digitação, e não uma idade. */
    public static final int MAX_PLAUSIBLE_AGE = 120;

    /**
     * O fuso mais a oeste do Brasil (UTC-5). Ali o dia vira por último, então ninguém completa anos no
     * Duora antes de completá-los em qualquer parte do país.
     */
    private static final ZoneId BIRTHDAY_ZONE = ZoneId.of("America/Rio_Branco");

    private AgePolicy() {
    }

    public static boolean isAdult(LocalDate birthDate, Instant now) {
        return ageOn(birthDate, now) >= ADULT_AGE;
    }

    public static boolean isPlausible(LocalDate birthDate, Instant now) {
        return ageOn(birthDate, now) <= MAX_PLAUSIBLE_AGE;
    }

    /**
     * Anos completos. O Period trata o nascido em 29 de fevereiro como o Código Civil (art. 132, § 3º):
     * nos anos não bissextos, ele completa anos em 1º de março. Data futura dá idade negativa.
     */
    private static int ageOn(LocalDate birthDate, Instant now) {
        return Period.between(birthDate, LocalDate.ofInstant(now, BIRTHDAY_ZONE)).getYears();
    }

}
