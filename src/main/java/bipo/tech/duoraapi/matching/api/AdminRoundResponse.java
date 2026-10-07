package bipo.tech.duoraapi.matching.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.matching.domain.RoundNumber;
import bipo.tech.duoraapi.matching.domain.RoundSummary;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A rodada para o ADMIN: quantos pares e quantas pessoas de fora, nunca quem formou par com quem
 * (docs/adr/0017). A semente do sorteio fica só no banco.
 */
record AdminRoundResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id do evento")
        UUID eventId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "" + RoundNumber.FIRST,
                maximum = "" + RoundNumber.MAX, description = "Número da rodada no evento")
        int number,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Quando a rodada começou, em UTC; não muda nas repetições do PUT")
        Instant startedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "0",
                maximum = "" + ApiSchemas.MAX_PAIRS, description = "Quantos pares se formaram")
        int pairCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "0",
                maximum = "" + ApiSchemas.MAX_PEOPLE, description = "Quantas pessoas ficaram sem par nesta rodada")
        int sittingOutCount) {

    static AdminRoundResponse of(RoundSummary summary) {
        return new AdminRoundResponse(summary.round().eventId(), summary.round().number().value(),
                summary.round().startedAt(), summary.pairCount(), summary.sittingOutCount());
    }

}
