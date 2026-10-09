package bipo.tech.duoraapi.chat.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.trustsafety.FiledReport;
import bipo.tech.duoraapi.trustsafety.ReportReason;
import bipo.tech.duoraapi.trustsafety.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A denúncia criada, com os mesmos campos que {@code GET /api/reports/{id}} devolve. Sem o texto copiado da
 * mensagem: quem denunciou já o tem, e a cópia é da moderação.
 */
@Schema(name = "ChatMessageReport")
record ChatMessageReportResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id da denúncia")
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id da conta denunciada: o par da rodada")
        UUID reportedAccountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ReportReason reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                maxLength = ApiSchemas.REPORT_DESCRIPTION_MAX_LENGTH, description = "O relato, ou null se não houve")
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Estado na moderação")
        ReportStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Quando a denúncia foi feita")
        Instant createdAt) {

    static ChatMessageReportResponse of(FiledReport report) {
        return new ChatMessageReportResponse(report.id(), report.reported().value(), report.reason(),
                report.description(), report.status(), report.createdAt());
    }

    /** O Spring MVC registra a resposta escrita por este toString em DEBUG: o relato fica de fora. */
    @Override
    public String toString() {
        return "ChatMessageReportResponse[id=" + id + ", reason=" + reason + ", status=" + status
                + ", description=redacted]";
    }

}
