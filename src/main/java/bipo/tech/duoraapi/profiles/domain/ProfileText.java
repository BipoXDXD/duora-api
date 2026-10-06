package bipo.tech.duoraapi.profiles.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Regras comuns aos textos livres do perfil, que outras pessoas vão ler. */
final class ProfileText {

    /**
     * Controle (NUL incluso: o PostgreSQL o recusa), formatação invisível (zero-width, controles de
     * direção que invertem o texto na tela) e espaços que não são o comum. Ficam de fora o espaço
     * comum e o ZWJ, que une emojis compostos.
     */
    static final Pattern FORBIDDEN_IN_LINE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}&&[^\\u0020\\u200D]]");

    /** Como {@link #FORBIDDEN_IN_LINE}, mas aceita a quebra de linha entre parágrafos. */
    static final Pattern FORBIDDEN_IN_PARAGRAPHS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}&&[^\\u0020\\u200D\\n]]");

    private ProfileText() {
    }

    /** Forma composta (NFC), quebra de linha do Windows como \n, sem espaço nas pontas. */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r\n", "\n").strip();
    }

    /** Em caracteres, como o length() do PostgreSQL, e não em unidades UTF-16. */
    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

}
