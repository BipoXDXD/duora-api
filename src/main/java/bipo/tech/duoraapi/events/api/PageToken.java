package bipo.tech.duoraapi.events.api;

import bipo.tech.duoraapi.config.KeysetPageToken;
import bipo.tech.duoraapi.events.application.PageCursor;

/** O pageToken das listas de eventos: o início e o id do último evento visto (docs/adr/0016). */
final class PageToken {

    /** Folga sobre o maior token válido, que tem 86 caracteres. */
    static final int MAX_LENGTH = 90;

    static final String PATTERN = KeysetPageToken.PATTERN;

    private PageToken() {
    }

    static String encode(PageCursor cursor) {
        return KeysetPageToken.encode(cursor.startsAt(), cursor.eventId());
    }

    /** @throws bipo.tech.duoraapi.config.InvalidPageParameterException se a API não gerou o token */
    static PageCursor decode(String token) {
        var position = KeysetPageToken.decode(token, MAX_LENGTH);
        return new PageCursor(position.instant(), position.id());
    }

}
