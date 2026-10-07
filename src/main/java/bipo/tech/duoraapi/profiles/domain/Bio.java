package bipo.tech.duoraapi.profiles.domain;

import java.util.Optional;

import bipo.tech.duoraapi.FieldErrorCode;

/** Apresentação curta, em parágrafos: até 300 caracteres, sem invisíveis. */
public record Bio(String value) {

    public static final int MAX_LENGTH = 300;

    private static final String FIELD = "bio";

    public Bio {
        if (value == null) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.REQUIRED, "bio is required");
        }
        value = ProfileText.normalize(value);
        if (value.isEmpty()) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.REQUIRED, "bio must not be blank");
        }
        if (ProfileText.length(value) > MAX_LENGTH) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.TOO_LONG,
                    "bio must have at most " + MAX_LENGTH + " characters");
        }
        if (ProfileText.FORBIDDEN_IN_PARAGRAPHS.matcher(value).find()) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.FORBIDDEN_CHARACTER,
                    "bio contains a forbidden character");
        }
    }

    /** Texto vazio ou só com espaços é ausência de bio, como quando o campo do formulário é apagado. */
    public static Optional<Bio> fromText(String text) {
        if (text == null || ProfileText.normalize(text).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Bio(text));
    }

    /** A bio é dado pessoal: fora dos logs. */
    @Override
    public String toString() {
        return "Bio[redacted]";
    }

}
