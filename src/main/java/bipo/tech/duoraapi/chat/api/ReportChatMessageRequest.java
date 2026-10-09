package bipo.tech.duoraapi.chat.api;

import jakarta.validation.constraints.NotNull;

import bipo.tech.duoraapi.trustsafety.ReportReason;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Só o motivo e o relato. A mensagem vem da rota, e quem denuncia, quem é denunciado, o texto copiado, o estado e
 * a data são do servidor: chave desconhecida é 400 (fail-on-unknown-properties). Os limites do relato são os de
 * {@code POST /api/reports}, conferidos no trustsafety.
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
record ReportChatMessageRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Motivo, da mesma lista de fileReport")
        @NotNull ReportReason reason,
        @Schema(types = {"string", "null"}, maxLength = ApiSchemas.REPORT_DESCRIPTION_MAX_LENGTH,
                description = "Relato livre em parágrafos, sem caracteres invisíveis; obrigatório com o motivo "
                        + "OTHER. Vazio ou só com espaços conta como ausente.")
        String description) {

    /** O Spring MVC registra o corpo lido por este toString em DEBUG: o relato fica de fora. */
    @Override
    public String toString() {
        return "ReportChatMessageRequest[reason=" + reason + ", description=redacted]";
    }

}
