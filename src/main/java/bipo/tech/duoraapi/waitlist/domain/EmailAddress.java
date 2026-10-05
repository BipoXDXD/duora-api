package bipo.tech.duoraapi.waitlist.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** E-mail normalizado (sem espaços nas pontas, minúsculo). Só existe se for válido. */
public record EmailAddress(String value) {

    /** Limite prático do RFC 5321 para um endereço completo. */
    public static final int MAX_LENGTH = 254;

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public EmailAddress {
        if (value == null) {
            throw new InvalidEmailAddressException("email is required");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH || !SHAPE.matcher(value).matches()) {
            throw new InvalidEmailAddressException("email is not a valid address");
        }
    }

}
