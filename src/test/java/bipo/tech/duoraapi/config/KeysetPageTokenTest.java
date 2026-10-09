package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.config.KeysetPageToken.Position;

class KeysetPageTokenTest {

    private static final int MAX_LENGTH = 90;
    private static final Instant INSTANT = Instant.parse("2026-11-01T22:00:00.123456Z");
    private static final UUID ID = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");

    @Test
    void decodesWhatItEncodes() {
        assertThat(KeysetPageToken.decode(KeysetPageToken.encode(INSTANT, ID), MAX_LENGTH))
                .isEqualTo(new Position(INSTANT, ID));
    }

    /** O token vai na query string: só caracteres que dispensam escape. */
    @Test
    void isSafeInAUrl() {
        assertThat(KeysetPageToken.encode(INSTANT, ID)).matches("[A-Za-z0-9_-]+").matches(KeysetPageToken.PATTERN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "não é base64", "%%%", "AAAA", "Zm9v", "+/+/"})
    void rejectsGarbage(String token) {
        assertThatThrownBy(() -> KeysetPageToken.decode(token, MAX_LENGTH))
                .isInstanceOf(InvalidPageParameterException.class)
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

        assertThatThrownBy(() -> KeysetPageToken.decode(token, MAX_LENGTH))
                .isInstanceOf(InvalidPageParameterException.class)
                .hasMessage("pageToken is invalid");
    }

    @Test
    void rejectsTokenLongerThanTheMaximumOfTheList() {
        assertThatThrownBy(() -> KeysetPageToken.decode("A".repeat(MAX_LENGTH + 1), MAX_LENGTH))
                .isInstanceOf(InvalidPageParameterException.class)
                .hasMessage("pageToken is invalid");
    }

    /** Fora de 1970 a 9999 não há item: o instante nem chega ao banco, que o recusaria com erro. */
    @Test
    void acceptsTheEdgesOfThePlausibleRange() {
        var earliest = new Position(Instant.EPOCH, ID);
        var latest = new Position(Instant.parse("9999-12-31T23:59:59.999999Z"), ID);

        assertThat(KeysetPageToken.decode(KeysetPageToken.encode(earliest.instant(), ID), MAX_LENGTH))
                .isEqualTo(earliest);
        assertThat(KeysetPageToken.decode(KeysetPageToken.encode(latest.instant(), ID), MAX_LENGTH))
                .isEqualTo(latest);
    }

}
