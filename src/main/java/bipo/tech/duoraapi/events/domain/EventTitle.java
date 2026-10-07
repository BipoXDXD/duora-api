package bipo.tech.duoraapi.events.domain;

import bipo.tech.duoraapi.FieldErrorCode;

/** Título do evento: uma linha, de 1 a 80 caracteres, sem invisíveis. */
public record EventTitle(String value) {

    public static final int MAX_LENGTH = 80;

    private static final String FIELD = "title";
    private static final String LENGTH_RULE = FIELD + " must have 1 to " + MAX_LENGTH + " characters";

    public EventTitle {
        if (value == null) {
            throw new InvalidEventException(FIELD, FieldErrorCode.REQUIRED, "title is required");
        }
        value = EventText.normalize(value);
        int length = EventText.length(value);
        if (length < 1) {
            throw new InvalidEventException(FIELD, FieldErrorCode.TOO_SHORT, LENGTH_RULE);
        }
        if (length > MAX_LENGTH) {
            throw new InvalidEventException(FIELD, FieldErrorCode.TOO_LONG, LENGTH_RULE);
        }
        if (EventText.FORBIDDEN_IN_LINE.matcher(value).find()) {
            throw new InvalidEventException(FIELD, FieldErrorCode.FORBIDDEN_CHARACTER,
                    "title contains a forbidden character");
        }
    }

}
