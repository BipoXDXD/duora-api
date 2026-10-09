package bipo.tech.duoraapi.config;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * O {@code pageToken} das listas paginadas por keyset (docs/adr/0005): o instante e o id do último item visto,
 * em Base64 URL-safe. Opaco para o cliente, mas não cifrado: não carrega nada que ele não viu na página, e
 * cada consulta continua filtrando por quem pede, então um token adulterado só muda onde a lista recomeça.
 */
public final class KeysetPageToken {

    /** Base64 URL-safe sem padding: o token vai na query string sem escape. */
    public static final String PATTERN = "^[A-Za-z0-9_-]+$";

    /**
     * Nenhum item existe fora de 1970 a 9999. O Instant aceita anos muito além do que o timestamptz guarda, e
     * um token adulterado com um deles viraria erro do banco (500) em vez de 400.
     */
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private static final String SEPARATOR = " ";

    private KeysetPageToken() {
    }

    /** O último item visto: o instante pelo qual a lista é ordenada e o id que desempata. */
    public record Position(Instant instant, UUID id) {

        public Position {
            Objects.requireNonNull(instant, "instant");
            Objects.requireNonNull(id, "id");
        }

    }

    public static String encode(Instant instant, UUID id) {
        String content = instant + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param maxLength o teto que a spec da lista declara para o token
     * @throws InvalidPageParameterException se o token não foi gerado por {@link #encode}
     */
    public static Position decode(String token, int maxLength) {
        if (token.isEmpty() || token.length() > maxLength) {
            throw invalid();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
                    .split(SEPARATOR, -1);
            if (parts.length != 2) {
                throw invalid();
            }
            UUID id = UUID.fromString(parts[1]);
            if (!id.toString().equals(parts[1])) {
                throw invalid();
            }
            Instant instant = Instant.parse(parts[0]);
            if (instant.isBefore(EARLIEST) || instant.isAfter(LATEST)) {
                throw invalid();
            }
            return new Position(instant, id);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalid();
        }
    }

    private static InvalidPageParameterException invalid() {
        return new InvalidPageParameterException("pageToken is invalid");
    }

}
