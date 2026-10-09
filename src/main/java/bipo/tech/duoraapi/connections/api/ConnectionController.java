package bipo.tech.duoraapi.connections.api;

import static bipo.tech.duoraapi.connections.api.ApiSchemas.PROBLEM_JSON;
import static bipo.tech.duoraapi.connections.api.ApiSchemas.PROBLEM_SCHEMA;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bipo.tech.duoraapi.config.MaxPageSize;
import bipo.tech.duoraapi.connections.application.ConnectionService;
import bipo.tech.duoraapi.identity.AccountId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/** As conexões de quem chama (docs/adr/0019): só as próprias, sem id de pessoa na rota. */
@RestController
@Tag(name = "connections", description = "Decisão privada depois de cada rodada e conexões por interesse mútuo")
class ConnectionController {

    static final String PATH = "/api/me/connections";
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final ConnectionService connections;

    ConnectionController(ConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping(PATH)
    @Operation(operationId = "listMyConnections", summary = "Lista as próprias conexões",
            description = "Pessoas com quem houve interesse mútuo depois de uma rodada, da conexão mais recente "
                    + "para a mais antiga, paginadas por cursor. A última página vem com nextPageToken null.")
    @ApiResponse(responseCode = "200", description = "Uma página das conexões")
    @ApiResponse(responseCode = "400", description = "maxPageSize fora de 1 a " + MAX_PAGE_SIZE + ", ou pageToken que a API não gerou",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    ConnectionsResponse mine(AccountId account,
            @Parameter(description = "Quantas conexões no máximo nesta página",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1",
                            maximum = "" + MAX_PAGE_SIZE, defaultValue = "" + DEFAULT_PAGE_SIZE))
            @RequestParam(name = "maxPageSize", required = false) String maxPageSizeText,
            @Parameter(description = "O nextPageToken da página anterior; ausente na primeira",
                    schema = @Schema(type = "string", pattern = ConnectionPageToken.PATTERN,
                            maxLength = ConnectionPageToken.MAX_LENGTH))
            @RequestParam(required = false) String pageToken) {
        int maxPageSize = MaxPageSize.parse(maxPageSizeText, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        var after = pageToken == null ? null : ConnectionPageToken.decode(pageToken);
        return ConnectionsResponse.of(connections.connectionsOf(account, maxPageSize, after));
    }

}
