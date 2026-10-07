package bipo.tech.duoraapi.events.api;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.PageCursor;

/**
 * O cursor de página como texto opaco para o cliente: Base64 URL-safe de "início id". Não é cifrado,
 * porque não carrega nada que o cliente não veja na própria página, e toda consulta continua filtrando
 * por estado e por dono; adulterá-lo só muda onde a lista recomeça (docs/adr/0016).
 */
final class PageToken {

    /** Folga sobre o maior token válido, que tem 86 caracteres. */
    static final int MAX_LENGTH = 90;

    /** Base64 URL-safe sem padding: o token vai na query string sem escape. */
    static final String PATTERN = "^[A-Za-z0-9_-]+$";

    /**
     * Nenhum evento existe fora de 1970 a 9999. O Instant aceita anos muito além do que o timestamptz
     * guarda, e um token adulterado com um deles viraria erro do banco (500) em vez de 400.
     */
    private static final Instant EARLIEST = Instant.EPOCH;
    private static final Instant LATEST = Instant.parse("9999-12-31T23:59:59.999999Z");

    private static final String SEPARATOR = " ";

    private PageToken() {
    }

    static String encode(PageCursor cursor) {
        String content = cursor.startsAt() + SEPARATOR + cursor.eventId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    static PageCursor decode(String token) {
        if (token.isEmpty() || token.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split(SEPARATOR, -1);
            if (parts.length != 2) {
                throw invalid();
            }
            UUID eventId = UUID.fromString(parts[1]);
            if (!eventId.toString().equals(parts[1])) {
                throw invalid();
            }
            Instant startsAt = Instant.parse(parts[0]);
            if (startsAt.isBefore(EARLIEST) || startsAt.isAfter(LATEST)) {
                throw invalid();
            }
            return new PageCursor(startsAt, eventId);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw invalid();
        }
    }

    private static InvalidPageRequestException invalid() {
        return new InvalidPageRequestException("pageToken is invalid");
    }

}
