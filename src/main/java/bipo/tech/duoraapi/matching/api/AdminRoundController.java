package bipo.tech.duoraapi.matching.api;

import static bipo.tech.duoraapi.matching.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.matching.api.ApiSchemas.PROBLEM_SCHEMA;

import java.net.URI;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.config.AccountRateLimit;
import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.matching.application.RoundOutcome;
import bipo.tech.duoraapi.matching.application.RoundService;
import bipo.tech.duoraapi.matching.domain.RoundNumber;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Rodadas de pareamento para o ADMIN (docs/adr/0017). O papel é conferido pela rota /api/admin/** em
 * SecurityConfiguration. A rodada é criada por PUT no próprio número: repetir devolve a mesma. O PUT gasta o
 * limite da conta ADMIN antes de tocar o banco, inclusive na repetição, que também lê os inscritos e trava
 * a rodada (docs/adr/0017).
 */
@RestController
@Tag(name = "admin-rounds", description = "Rodadas de pareamento de um evento, iniciadas pelo ADMIN")
class AdminRoundController {

    static final String PATH = "/api/admin/events/{eventId}/rounds/{number}";

    private final RoundService rounds;
    private final AccountRateLimit rateLimit;

    AdminRoundController(RoundService rounds,
            @Qualifier(RoundRateLimitConfiguration.BEAN_NAME) AccountRateLimit rateLimit) {
        this.rounds = rounds;
        this.rateLimit = rateLimit;
    }

    /** 201 com Location na primeira vez; 200 com a mesma rodada nas repetições. */
    @PutMapping(PATH)
    @Operation(operationId = "startRound", summary = "Inicia uma rodada de pareamento do evento",
            description = "Sem corpo. Sorteia os pares entre os inscritos: o máximo de pares possível, sem par "
                    + "bloqueado nem par que já se formou no evento, com prioridade para quem ficou de fora mais "
                    + "vezes. Idempotente pela chave evento + número: repetir, inclusive ao mesmo tempo, devolve "
                    + "a mesma rodada, por isso dispensa If-Match e Idempotency-Key (docs/adr/0017). A rodada N "
                    + "exige a N-1, e o evento precisa estar publicado e em andamento. Cada chamada, repetida ou "
                    + "não, gasta o limite da conta ADMIN: " + RoundRateLimitProperties.DEFAULT_CAPACITY + " por hora, "
                    + "repostas aos poucos.")
    @ApiResponse(responseCode = "201", description = "A rodada criada",
            headers = @Header(name = "Location", required = true, description = "Endereço da rodada",
                    schema = @Schema(type = "string", format = "uri", maxLength = ApiSchemas.LOCATION_MAX_LENGTH)))
    @ApiResponse(responseCode = "200", description = "A rodada que já existia")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID, ou número fora de " + ApiSchemas.FIRST_ROUND + " a "
                    + ApiSchemas.LAST_ROUND,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há evento com esse id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409",
            description = "O evento não está em andamento (rascunho, cancelado, antes do início ou depois do fim), "
                    + "ou a rodada anterior ainda não começou",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "429", description = "Limite de rodadas desta conta ADMIN esgotado",
            headers = @Header(name = "Retry-After", required = true,
                    description = "Segundos até a próxima chamada ficar disponível",
                    schema = @Schema(type = "integer", format = "int64", minimum = "0",
                            maximum = AccountRateLimit.MAX_RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503",
            description = "Outro pedido está iniciando a mesma rodada, ou o limite desta conta não pôde ser contado; "
                    + "nada foi gravado",
            headers = @Header(name = "Retry-After", required = true, description = "Segundos até tentar de novo",
                    schema = @Schema(type = "integer", format = "int32",
                            minimum = MatchingExceptionHandler.RETRY_AFTER_SECONDS,
                            maximum = MatchingExceptionHandler.RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<AdminRoundResponse> start(
            @Parameter(description = ApiSchemas.EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
                    format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId,
            @Parameter(description = ApiSchemas.ROUND_NUMBER_DESCRIPTION, schema = @Schema(type = "integer",
                    format = "int32", minimum = "" + RoundNumber.FIRST, maximum = "" + RoundNumber.MAX))
            @PathVariable int number, AccountId admin) {
        rateLimit.consume(admin);
        RoundOutcome outcome = rounds.start(eventId, new RoundNumber(number));
        var body = AdminRoundResponse.of(outcome.round());
        if (outcome.created()) {
            return ResponseEntity.created(URI.create("/api/admin/events/" + eventId + "/rounds/" + number))
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping(PATH)
    @Operation(operationId = "getRound", summary = "Lê uma rodada do evento",
            description = "Quantos pares se formaram e quantas pessoas ficaram de fora, nunca quem.")
    @ApiResponse(responseCode = "200", description = "A rodada")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID, ou número fora de " + ApiSchemas.FIRST_ROUND + " a "
                    + ApiSchemas.LAST_ROUND,
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "O evento não tem rodada com esse número",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    AdminRoundResponse find(
            @Parameter(description = ApiSchemas.EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
                    format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId,
            @Parameter(description = ApiSchemas.ROUND_NUMBER_DESCRIPTION, schema = @Schema(type = "integer",
                    format = "int32", minimum = "" + RoundNumber.FIRST, maximum = "" + RoundNumber.MAX))
            @PathVariable int number) {
        return AdminRoundResponse.of(rounds.find(eventId, new RoundNumber(number)));
    }

}
