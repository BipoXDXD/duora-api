package bipo.tech.duoraapi.trustsafety.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.trustsafety.domain.Report;
import bipo.tech.duoraapi.trustsafety.domain.ReportDescription;
import bipo.tech.duoraapi.trustsafety.domain.ReportReason;
import bipo.tech.duoraapi.trustsafety.domain.ReportStatus;

/** A denúncia como quem a fez a vê. Sem a conta de quem denunciou: é quem está lendo. */
record ReportResponse(UUID id, UUID reportedAccountId, ReportReason reason, String description, ReportStatus status,
        Instant createdAt) {

    static ReportResponse of(Report report) {
        var description = report.description() == null ? null : report.description().value();
        return new ReportResponse(report.id(), report.reported().value(), report.reason(), description,
                report.status(), report.createdAt());
    }

    /** O relato é dado sensível: fora de qualquer log que imprima a resposta. */
    @Override
    public String toString() {
        return "ReportResponse[id=" + id + ", " + ReportDescription.class.getSimpleName() + "=redacted]";
    }

}
