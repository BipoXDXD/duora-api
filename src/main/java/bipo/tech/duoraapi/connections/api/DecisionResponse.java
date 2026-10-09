package bipo.tech.duoraapi.connections.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.connections.domain.Decision;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A decisão de quem chama, e só ela: nada da decisão do par nem se a conexão se formou (docs/adr/0019).
 * Também não traz o id do par, que a pessoa já lê no pareamento da rodada.
 */
record DecisionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id do evento")
        UUID eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = ApiSchemas.FIRST_ROUND,
                maximum = ApiSchemas.LAST_ROUND, description = "Número da rodada no evento")
        int roundNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "A escolha de quem chama: true para continuar em contato")
        boolean interested,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Quando a decisão foi gravada")
        Instant decidedAt) {

    static DecisionResponse of(Decision decision) {
        return new DecisionResponse(decision.eventId(), decision.roundNumber(), decision.interested(),
                decision.decidedAt());
    }

    /** O Spring MVC registra a resposta por este toString em DEBUG: a escolha é privada e fica de fora. */
    @Override
    public String toString() {
        return "DecisionResponse[eventId=" + eventId + ", roundNumber=" + roundNumber + ", choice=redacted]";
    }

}
