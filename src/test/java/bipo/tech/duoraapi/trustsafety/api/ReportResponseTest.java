package bipo.tech.duoraapi.trustsafety.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.trustsafety.domain.ReportReason;
import bipo.tech.duoraapi.trustsafety.domain.ReportStatus;

/** Como em {@link FileReportRequestTest}: o MVC registra a resposta escrita pelo toString em DEBUG. */
class ReportResponseTest {

    @Test
    void doesNotExposeTheDescriptionInToString() {
        var response = new ReportResponse(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"),
                UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8c"), ReportReason.OTHER, "ameaçou me encontrar",
                ReportStatus.OPEN, Instant.parse("2026-10-05T12:00:00Z"));

        assertThat(response.toString()).doesNotContain("ameaçou");
    }

}
