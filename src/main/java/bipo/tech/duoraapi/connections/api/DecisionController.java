package bipo.tech.duoraapi.connections.api;

import static bipo.tech.duoraapi.connections.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.connections.api.ApiSchemas.PROBLEM_SCHEMA;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import bipo.tech.duoraapi.connections.application.DecisionOutcome;
import bipo.tech.duoraapi.connections.application.DecisionService;
import bipo.tech.duoraapi.identity.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * A decisão privada de quem chama sobre o par de uma rodada, como sub-recurso singular (docs/adr/0019): não
 * há id de pessoa na rota, então não há como ler ou gravar a decisão de outra pessoa.
 */
@RestController
@Tag(name = "connections", description = "Decisão privada depois de cada rodada e conexões por interesse mútuo")
class DecisionController {

    static final String PATH = "/api/events/{eventId}/rounds/{number}/decision";

    private final DecisionService decisions;

    DecisionController(DecisionService decisions) {
        this.decisions = decisions;
    }

    /** 201 com Location na primeira vez; 200 com a mesma decisão nas repetições com a mesma escolha. */
    @PutMapping(PATH)
    @Operation(operationId = "decideAboutMyPartner", summary = "Decide se continua em contato com o par da rodada",
            description = "Uma decisão por pessoa e rodada, e final: repetir a mesma escolha devolve a mesma "
                    + "decisão (200); a outra escolha é recusada (409). A resposta é a mesma qualquer que seja a "
                    + "decisão do par; se os dois disserem sim, a conexão aparece em listMyConnections.")
    @ApiResponse(responseCode = "201", description = "A decisão gravada",
            headers = @Header(name = "Location", required = true, description = "Endereço da decisão",
                    schema = @Schema(type = "string", format = "uri", maxLength = ApiSchemas.LOCATION_MAX_LENGTH)))
    @ApiResponse(responseCode = "200", description = "A decisão que já existia, com a mesma escolha")
    @ApiResponse(responseCode = "400",
            description = "Id que não é UUID, número fora de 1 a 100, interested ausente ou que não é booleano, "
                    + "JSON malformado ou campo desconhecido",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404",
            description = "Quem chama não formou par nessa rodada: ficou de fora, não estava no sorteio ou a rodada "
                    + "não existe",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "409", description = "Quem chama já decidiu nessa rodada, com a outra escolha",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "503",
            description = "A decisão do par na mesma rodada demorou além do teto; nada foi gravado",
            headers = @Header(name = "Retry-After", required = true, description = "Segundos até tentar de novo",
                    schema = @Schema(type = "integer", format = "int32",
                            minimum = ConnectionsExceptionHandler.RETRY_AFTER_SECONDS,
                            maximum = ConnectionsExceptionHandler.RETRY_AFTER_SECONDS)),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ResponseEntity<DecisionResponse> decide(
            @Parameter(description = ApiSchemas.EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
                    format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId,
            @Parameter(description = ApiSchemas.ROUND_NUMBER_DESCRIPTION, schema = @Schema(type = "integer",
                    format = "int32", minimum = ApiSchemas.FIRST_ROUND, maximum = ApiSchemas.LAST_ROUND))
            @PathVariable int number,
            AccountId account,
            @Valid @RequestBody DecideRequest request) {
        int roundNumber = RoundNumberParameter.validated(number);
        DecisionOutcome outcome = decisions.decide(eventId, roundNumber, account, request.interested());
        var body = DecisionResponse.of(outcome.decision());
        if (outcome.created()) {
            URI location = UriComponentsBuilder.fromPath(PATH).buildAndExpand(eventId, roundNumber).toUri();
            return ResponseEntity.created(location).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping(PATH)
    @Operation(operationId = "getMyDecision", summary = "Lê a própria decisão sobre o par da rodada",
            description = "Só a de quem chama. Sem decisão gravada, 404, tenha a pessoa formado par ou não.")
    @ApiResponse(responseCode = "200", description = "A decisão de quem chama")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID, ou número fora de 1 a 100",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Quem chama não decidiu nessa rodada",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    DecisionResponse mine(
            @Parameter(description = ApiSchemas.EVENT_ID_DESCRIPTION, schema = @Schema(type = "string",
                    format = "uuid", minLength = ApiSchemas.UUID_LENGTH, maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID eventId,
            @Parameter(description = ApiSchemas.ROUND_NUMBER_DESCRIPTION, schema = @Schema(type = "integer",
                    format = "int32", minimum = ApiSchemas.FIRST_ROUND, maximum = ApiSchemas.LAST_ROUND))
            @PathVariable int number,
            AccountId account) {
        return DecisionResponse.of(decisions.decisionOf(eventId, RoundNumberParameter.validated(number), account));
    }

}
