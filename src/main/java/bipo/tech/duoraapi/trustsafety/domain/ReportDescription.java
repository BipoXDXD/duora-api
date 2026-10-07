package bipo.tech.duoraapi.trustsafety.domain;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

import bipo.tech.duoraapi.FieldErrorCode;

/**
 * O relato livre de quem denuncia, em parágrafos: até 1000 caracteres, sem controle nem invisíveis.
 * É dado sensível (pode citar terceiros e o próprio denunciante): nunca vai para log.
 */
public record ReportDescription(String value) {

    public static final int MAX_LENGTH = 1000;

    private static final String FIELD = "description";

    /**
     * Controle (NUL incluso: o PostgreSQL o recusa), formatação invisível e espaços que não são o
     * comum. Ficam de fora o espaço comum, o ZWJ dos emojis compostos e a quebra de linha.
     */
    private static final Pattern FORBIDDEN = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}&&[^\\u0020\\u200D\\n]]");

    public ReportDescription {
        if (value == null) {
            throw new InvalidReportException(FIELD, FieldErrorCode.REQUIRED, "description is required");
        }
        value = normalize(value);
        if (value.isEmpty()) {
            throw new InvalidReportException(FIELD, FieldErrorCode.REQUIRED, "description must not be blank");
        }
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            throw new InvalidReportException(FIELD, FieldErrorCode.TOO_LONG,
                    "description must have at most " + MAX_LENGTH + " characters");
        }
        if (FORBIDDEN.matcher(value).find()) {
            throw new InvalidReportException(FIELD, FieldErrorCode.FORBIDDEN_CHARACTER,
                    "description contains a forbidden character");
        }
    }

    /** Texto vazio ou só com espaços é ausência de descrição. */
    public static Optional<ReportDescription> fromText(String text) {
        if (text == null || normalize(text).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReportDescription(text));
    }

    @Override
    public String toString() {
        return "ReportDescription[redacted]";
    }

    /** Forma composta (NFC), quebra de linha do Windows como \n, sem espaço nas pontas. */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r\n", "\n").strip();
    }

}
