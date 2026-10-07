package bipo.tech.duoraapi.events.domain;

import bipo.tech.duoraapi.FieldErrorCode;

/**
 * Quantas pessoas podem se inscrever. Pelo menos 2, porque o encontro é em pares; no máximo 200, o
 * dobro do que o plano (§8) simula na carga, para um erro de digitação não abrir um evento sem limite.
 */
public record Capacity(int places) {

    public static final int MIN_PLACES = 2;
    public static final int MAX_PLACES = 200;

    private static final String FIELD = "capacity";
    private static final String RANGE_RULE = FIELD + " must be between " + MIN_PLACES + " and " + MAX_PLACES;

    public Capacity {
        if (places < MIN_PLACES) {
            throw new InvalidEventException(FIELD, FieldErrorCode.BELOW_MINIMUM, RANGE_RULE);
        }
        if (places > MAX_PLACES) {
            throw new InvalidEventException(FIELD, FieldErrorCode.ABOVE_MAXIMUM, RANGE_RULE);
        }
    }

    public boolean isFilledBy(long registrations) {
        return registrations >= places;
    }

}
