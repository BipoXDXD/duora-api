package bipo.tech.duoraapi.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.events.application.PageCursor;

class PageTokenTest {

    private static final PageCursor CURSOR = new PageCursor(Instant.parse("2026-11-01T22:00:00.123456Z"),
            UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"));

    @Test
    void decodesWhatItEncodes() {
        assertThat(PageToken.decode(PageToken.encode(CURSOR))).isEqualTo(CURSOR);
    }

    /** O token vai na query string: só caracteres que dispensam escape. */
    @Test
    void isSafeInAUrl() {
        assertThat(PageToken.encode(CURSOR)).matches("[A-Za-z0-9_-]+").matches(PageToken.PATTERN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "não é base64", "%%%", "AAAA", "Zm9v", "+/+/"})
    void rejectsGarbage(String token) {
        assertThatThrownBy(() -> PageToken.decode(token))
                .isInstanceOf(InvalidPageRequestException.class)
                .hasMessage("pageToken is invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01T22:00:00Z", "2026-11-01T22:00:00Z 1-1-1-1-1",
            "2026-11-01T22:00:00Z 01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b extra",
            "ontem 01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b", " 01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b",
            "+999999999-12-31T23:59:59Z 01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b",
            "+10000-01-01T00:00:00Z 01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b",
            "1969-12-31T23:59:59.999999Z 01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"})
    void rejectsWellEncodedTokenWithInvalidContent(String content) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(content.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PageToken.decode(token))
                .isInstanceOf(InvalidPageRequestException.class)
                .hasMessage("pageToken is invalid");
    }

    @Test
    void rejectsTokenLongerThanAnyValidOne() {
        assertThatThrownBy(() -> PageToken.decode("A".repeat(PageToken.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidPageRequestException.class)
                .hasMessage("pageToken is invalid");
    }

    /** Fora de 1970 a 9999 não há evento: o instante nem chega ao banco, que o recusaria com erro. */
    @Test
    void acceptsTheEdgesOfThePlausibleRange() {
        var earliest = new PageCursor(Instant.EPOCH, CURSOR.eventId());
        var latest = new PageCursor(Instant.parse("9999-12-31T23:59:59.999999Z"), CURSOR.eventId());

        assertThat(PageToken.decode(PageToken.encode(earliest))).isEqualTo(earliest);
        assertThat(PageToken.decode(PageToken.encode(latest))).isEqualTo(latest);
    }

    @Test
    void validTokenFitsTheMaximumLength() {
        var latest = new PageCursor(Instant.parse("9999-12-31T23:59:59.999999Z"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        assertThat(PageToken.encode(latest)).hasSizeLessThanOrEqualTo(PageToken.MAX_LENGTH);
    }

}
