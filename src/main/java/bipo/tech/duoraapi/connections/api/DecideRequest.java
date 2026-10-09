package bipo.tech.duoraapi.connections.api;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Só a escolha de quem decide. Evento, rodada e par vêm da rota e do matching; chave desconhecida é 400
 * (fail-on-unknown-properties). A escolha é um booleano JSON de verdade
 * ({@link StrictBooleanDeserializer}).
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
record DecideRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "true para continuar em contato com o par da rodada, false para não")
        @JsonDeserialize(using = StrictBooleanDeserializer.class) @NotNull Boolean interested) {

    /** O Spring MVC registra o corpo lido por este toString em DEBUG: a escolha é privada e fica de fora. */
    @Override
    public String toString() {
        return "DecideRequest[choice=redacted]";
    }

}
