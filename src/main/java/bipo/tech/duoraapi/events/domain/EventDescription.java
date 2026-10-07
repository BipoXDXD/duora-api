package bipo.tech.duoraapi.events.domain;

import bipo.tech.duoraapi.FieldErrorCode;

/** Descrição curta do evento, em parágrafos: de 1 a 500 caracteres, sem invisíveis. */
public record EventDescription(String value) {

    public static final int MAX_LENGTH = 500;

    private static final String FIELD = "description";
    private static final String LENGTH_RULE = FIELD + " must have 1 to " + MAX_LENGTH + " characters";

    public EventDescription {
        if (value == null) {
            throw new InvalidEventException(FIELD, FieldErrorCode.REQUIRED, "description is required");
        }
        value = EventText.normalize(value);
        int length = EventText.length(value);
        if (length < 1) {
            throw new InvalidEventException(FIELD, FieldErrorCode.TOO_SHORT, LENGTH_RULE);
        }
        if (length > MAX_LENGTH) {
            throw new InvalidEventException(FIELD, FieldErrorCode.TOO_LONG, LENGTH_RULE);
        }
        if (EventText.FORBIDDEN_IN_PARAGRAPHS.matcher(value).find()) {
            throw new InvalidEventException(FIELD, FieldErrorCode.FORBIDDEN_CHARACTER,
                    "description contains a forbidden character");
        }
    }

}
