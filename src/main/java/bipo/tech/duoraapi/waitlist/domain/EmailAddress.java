package bipo.tech.duoraapi.waitlist.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** E-mail normalizado (sem espaços nas pontas, minúsculo). Só existe se for válido. */
public record EmailAddress(String value) {

    /** Limite prático do RFC 5321 para um endereço completo. */
    public static final int MAX_LENGTH = 254;

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * Controle (NUL incluso), formatação invisível (zero-width) e espaços Unicode, que o \\s não
     * cobre: o PostgreSQL recusa o NUL, e o resto entraria como lixo que escapa do UNIQUE.
     */
    private static final Pattern CONTROL_OR_INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Z}]");

    public EmailAddress {
        if (value == null) {
            throw new InvalidEmailAddressException("email is required");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH || CONTROL_OR_INVISIBLE.matcher(value).find()
                || !SHAPE.matcher(value).matches()) {
            throw new InvalidEmailAddressException("email is not a valid address");
        }
    }

    /** Dado pessoal: não vai para log nem mensagem de erro que imprima o objeto. */
    @Override
    public String toString() {
        return "EmailAddress[value=<redacted>]";
    }

}
