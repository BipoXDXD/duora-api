package bipo.tech.duoraapi.profiles.domain;

import bipo.tech.duoraapi.FieldErrorCode;

/** Nome que as outras pessoas veem: uma linha, de 1 a 50 caracteres, sem invisíveis. */
public record DisplayName(String value) {

    public static final int MAX_LENGTH = 50;

    private static final String FIELD = "displayName";
    private static final String LENGTH_RULE = FIELD + " must have 1 to " + MAX_LENGTH + " characters";

    public DisplayName {
        if (value == null) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.REQUIRED, "displayName is required");
        }
        value = ProfileText.normalize(value);
        int length = ProfileText.length(value);
        if (length < 1) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.TOO_SHORT, LENGTH_RULE);
        }
        if (length > MAX_LENGTH) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.TOO_LONG, LENGTH_RULE);
        }
        if (ProfileText.FORBIDDEN_IN_LINE.matcher(value).find()) {
            throw new InvalidProfileException(FIELD, FieldErrorCode.FORBIDDEN_CHARACTER,
                    "displayName contains a forbidden character");
        }
    }

    /** O nome é dado pessoal: fora dos logs. */
    @Override
    public String toString() {
        return "DisplayName[redacted]";
    }

}
