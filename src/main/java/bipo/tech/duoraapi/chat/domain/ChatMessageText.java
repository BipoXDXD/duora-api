package bipo.tech.duoraapi.chat.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

import bipo.tech.duoraapi.FieldErrorCode;

/**
 * O texto de uma mensagem do chat: de 1 a 500 caracteres depois de normalizado, em parágrafos, sem controle
 * nem invisíveis (docs/adr/0021). As regras são as dos textos do perfil e do evento, repetidas aqui porque
 * cada módulo é dono do próprio modelo (docs/adr/0007). Conversa íntima é dado pessoal: nunca vai para log.
 */
public record ChatMessageText(String value) {

    public static final int MAX_LENGTH = 500;

    private static final String FIELD = "text";
    private static final String LENGTH_RULE = FIELD + " must have 1 to " + MAX_LENGTH + " characters";

    /**
     * Controle (NUL incluso: o PostgreSQL o recusa), formatação invisível (zero-width, controles de direção
     * que invertem o texto na tela) e espaços que não são o comum. Ficam de fora o espaço comum, o ZWJ dos
     * emojis compostos e a quebra de linha.
     */
    private static final Pattern FORBIDDEN = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}&&[^\\u0020\\u200D\\n]]");

    public ChatMessageText {
        if (value == null) {
            throw new InvalidChatMessageException(FIELD, FieldErrorCode.REQUIRED, "text is required");
        }
        value = normalize(value);
        int length = value.codePointCount(0, value.length());
        if (length < 1) {
            throw new InvalidChatMessageException(FIELD, FieldErrorCode.TOO_SHORT, LENGTH_RULE);
        }
        if (length > MAX_LENGTH) {
            throw new InvalidChatMessageException(FIELD, FieldErrorCode.TOO_LONG, LENGTH_RULE);
        }
        if (FORBIDDEN.matcher(value).find()) {
            throw new InvalidChatMessageException(FIELD, FieldErrorCode.FORBIDDEN_CHARACTER,
                    "text contains a forbidden character");
        }
    }

    @Override
    public String toString() {
        return "ChatMessageText[redacted]";
    }

    /** Forma composta (NFC), quebra de linha do Windows como \n, sem espaço nas pontas. */
    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r\n", "\n").strip();
    }

}
