package bipo.tech.duoraapi.trustsafety;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Uma denúncia registrada, como quem a fez pode vê-la: o mesmo que {@code GET /api/reports/{id}} devolve.
 *
 * @param description o relato, ou null quando quem denunciou não escreveu nada
 */
public record FiledReport(UUID id, AccountId reported, ReportReason reason, String description,
        ReportStatus status, Instant createdAt) {

    public FiledReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reported, "reported");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** O relato pode citar terceiros e o próprio denunciante: nunca vai para log. */
    @Override
    public String toString() {
        return "FiledReport[id=" + id + ", reason=" + reason + ", status=" + status + ", description=redacted]";
    }

}
