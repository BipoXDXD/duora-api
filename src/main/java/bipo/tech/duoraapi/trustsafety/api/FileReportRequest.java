package bipo.tech.duoraapi.trustsafety.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import bipo.tech.duoraapi.trustsafety.domain.ReportDescription;
import bipo.tech.duoraapi.trustsafety.domain.ReportReason;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Só o que quem denuncia informa. Id, autoria, estado e data são do servidor: chave desconhecida é 400
 * (fail-on-unknown-properties). Os limites da descrição ficam no domínio.
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
record FileReportRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id da conta denunciada")
        @NotNull UUID reportedAccountId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Motivo, de uma lista fechada")
        @NotNull ReportReason reason,
        @Schema(types = {"string", "null"}, maxLength = ReportDescription.MAX_LENGTH,
                description = "Relato livre em parágrafos, sem caracteres invisíveis; obrigatório "
                        + "com o motivo OTHER. "
                        + "Vazio ou só com espaços conta como ausente.")
        String description) {

    /** O Spring MVC registra o corpo lido por este toString em DEBUG: o relato fica de fora. */
    @Override
    public String toString() {
        return "FileReportRequest[reportedAccountId=" + reportedAccountId + ", reason=" + reason
                + ", description=redacted]";
    }

}
