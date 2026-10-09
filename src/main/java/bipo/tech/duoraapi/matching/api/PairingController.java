package bipo.tech.duoraapi.matching.api;

import static bipo.tech.duoraapi.matching.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.matching.api.ApiSchemas.PROBLEM_SCHEMA;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.matching.application.RoundService;
import bipo.tech.duoraapi.matching.domain.RoundNumber;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * O par de quem chama numa rodada, como sub-recurso singular (docs/adr/0017): a consulta só alcança o
 * próprio assento, então não há como ver o par de outra pessoa.
 */
@RestController
@Tag(name = "pairings", description = "O par de quem está logado em cada rodada de um evento")
class PairingController {

    static final String PATH = "/api/events/{eventId}/rounds/{number}/pairing";

    private final RoundService rounds;

    PairingController(RoundService rounds) {
        this.rounds = rounds;
    }

    @GetMapping(PATH)
    @Operation(operationId = "getMyPairing", summary = "Lê o próprio par na rodada",
            description = "O id da conta do par, ou null se quem chama ficou de fora nesta rodada. Sem lugar na "
                    + "rodada, 404, exista o evento ou a rodada ou não.")
    @ApiResponse(responseCode = "200", description = "O lugar de quem chama na rodada")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID, ou número fora de " + ApiSchemas.FIRST_ROUND + " a "
                    + ApiSchemas.LAST_ROUND,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Quem chama não estava no sorteio dessa rodada",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    PairingResponse mine(
            @Parameter(description = ApiSchemas.EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
                    format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId,
            @Parameter(description = ApiSchemas.ROUND_NUMBER_DESCRIPTION, schema = @Schema(type = "integer",
                    format = "int32", minimum = "" + RoundNumber.FIRST, maximum = "" + RoundNumber.MAX))
            @PathVariable int number,
            AccountId account) {
        var roundNumber = new RoundNumber(number);
        return PairingResponse.of(eventId, roundNumber, rounds.seatOf(eventId, roundNumber, account));
    }

}
