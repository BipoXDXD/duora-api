package bipo.tech.duoraapi.trustsafety.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.trustsafety.domain.Report;
import bipo.tech.duoraapi.trustsafety.domain.ReportDescription;
import bipo.tech.duoraapi.trustsafety.domain.ReportReason;
import bipo.tech.duoraapi.trustsafety.domain.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** A denúncia como quem a fez a vê. Sem a conta de quem denunciou: é quem está lendo. */
record ReportResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id da denúncia")
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id da conta denunciada")
        UUID reportedAccountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ReportReason reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                maxLength = ReportDescription.MAX_LENGTH, description = "O relato, ou null se não houve")
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Estado na moderação")
        ReportStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Quando a denúncia foi feita")
        Instant createdAt) {

    static ReportResponse of(Report report) {
        var description = report.description() == null ? null : report.description().value();
        return new ReportResponse(report.id(), report.reported().value(), report.reason(), description,
                report.status(), report.createdAt());
    }

    /** O Spring MVC registra a resposta escrita por este toString em DEBUG: o relato fica de fora. */
    @Override
    public String toString() {
        return "ReportResponse[id=" + id + ", reason=" + reason + ", status=" + status + ", description=redacted]";
    }

}
