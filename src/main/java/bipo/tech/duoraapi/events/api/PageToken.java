package bipo.tech.duoraapi.events.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.PageCursor;

/**
 * O cursor de página como texto opaco para o cliente: Base64 URL-safe de "início id". Não é cifrado,
 * porque não carrega nada que o cliente não veja na própria página, e toda consulta continua filtrando
 * por estado e por dono; adulterá-lo só muda onde a lista recomeça (docs/adr/0016).
 */
final class PageToken {

    /** Folga sobre o maior token válido, que tem 91 caracteres. */
    static final int MAX_LENGTH = 100;

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
            return new PageCursor(Instant.parse(parts[0]), eventId);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static InvalidPageRequestException invalid() {
        return new InvalidPageRequestException("pageToken is invalid");
    }

}
