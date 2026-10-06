package bipo.tech.duoraapi.events.domain;

/** Título do evento: uma linha, de 1 a 80 caracteres, sem invisíveis. */
public record EventTitle(String value) {

    public static final int MAX_LENGTH = 80;

    public EventTitle {
        if (value == null) {
            throw new InvalidEventException("title is required");
        }
        value = EventText.normalize(value);
        int length = EventText.length(value);
        if (length < 1 || length > MAX_LENGTH) {
            throw new InvalidEventException("title must have 1 to " + MAX_LENGTH + " characters");
        }
        if (EventText.FORBIDDEN_IN_LINE.matcher(value).find()) {
            throw new InvalidEventException("title contains a forbidden character");
        }
    }

}
