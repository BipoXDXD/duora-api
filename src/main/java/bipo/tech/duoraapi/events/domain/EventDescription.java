package bipo.tech.duoraapi.events.domain;

/** Descrição curta do evento, em parágrafos: de 1 a 500 caracteres, sem invisíveis. */
public record EventDescription(String value) {

    public static final int MAX_LENGTH = 500;

    public EventDescription {
        if (value == null) {
            throw new InvalidEventException("description is required");
        }
        value = EventText.normalize(value);
        int length = EventText.length(value);
        if (length < 1 || length > MAX_LENGTH) {
            throw new InvalidEventException("description must have 1 to " + MAX_LENGTH + " characters");
        }
        if (EventText.FORBIDDEN_IN_PARAGRAPHS.matcher(value).find()) {
            throw new InvalidEventException("description contains a forbidden character");
        }
    }

}
