package bipo.tech.duoraapi.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.config.InvalidPageParameterException;
import bipo.tech.duoraapi.events.application.PageCursor;

/** O formato do token é do {@code config.KeysetPageTokenTest}; aqui, só o que é da lista de eventos. */
class PageTokenTest {

    private static final PageCursor CURSOR = new PageCursor(Instant.parse("2026-11-01T22:00:00.123456Z"),
            UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"));

    @Test
    void decodesWhatItEncodes() {
        assertThat(PageToken.decode(PageToken.encode(CURSOR))).isEqualTo(CURSOR);
    }

    @Test
    void rejectsTokenLongerThanAnyValidOne() {
        assertThatThrownBy(() -> PageToken.decode("A".repeat(PageToken.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidPageParameterException.class)
                .hasMessage("pageToken is invalid");
    }

    @Test
    void validTokenFitsTheMaximumLength() {
        var latest = new PageCursor(Instant.parse("9999-12-31T23:59:59.999999Z"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));

        assertThat(PageToken.encode(latest)).hasSizeLessThanOrEqualTo(PageToken.MAX_LENGTH);
    }

}
