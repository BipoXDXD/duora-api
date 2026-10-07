package bipo.tech.duoraapi.matching.api;

import java.util.UUID;

import bipo.tech.duoraapi.matching.domain.RoundNumber;
import bipo.tech.duoraapi.matching.domain.Seat;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * O lugar de quem chama numa rodada: o id da conta do par, ou null para quem ficou de fora. O que mais
 * mostrar do par (nome, foto) é decisão pendente (docs/adr/0017).
 */
record PairingResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id do evento")
        UUID eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "" + RoundNumber.FIRST,
                maximum = "" + RoundNumber.MAX, description = "Número da rodada no evento")
        int roundNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid",
                minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH,
                description = "Id da conta do par nesta rodada, ou null se quem chama ficou de fora")
        UUID partnerAccountId) {

    static PairingResponse of(UUID eventId, RoundNumber number, Seat seat) {
        UUID partner = switch (seat) {
            case Seat.Paired paired -> paired.partner().value();
            case Seat.SittingOut sittingOut -> null;
        };
        return new PairingResponse(eventId, number.value(), partner);
    }

}
