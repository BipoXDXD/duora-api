package bipo.tech.duoraapi.profiles.domain;

/** Nome que as outras pessoas veem: uma linha, de 1 a 50 caracteres, sem invisíveis. */
public record DisplayName(String value) {

    public static final int MAX_LENGTH = 50;

    public DisplayName {
        if (value == null) {
            throw new InvalidProfileException("displayName is required");
        }
        value = ProfileText.normalize(value);
        int length = ProfileText.length(value);
        if (length < 1 || length > MAX_LENGTH) {
            throw new InvalidProfileException("displayName must have 1 to " + MAX_LENGTH + " characters");
        }
        if (ProfileText.FORBIDDEN_IN_LINE.matcher(value).find()) {
            throw new InvalidProfileException("displayName contains a forbidden character");
        }
    }

    /** O nome é dado pessoal: fora dos logs. */
    @Override
    public String toString() {
        return "DisplayName[redacted]";
    }

}
