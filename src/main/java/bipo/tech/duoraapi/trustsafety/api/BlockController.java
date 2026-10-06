package bipo.tech.duoraapi.trustsafety.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.application.BlockService;
import bipo.tech.duoraapi.trustsafety.domain.SelfBlockException;
import bipo.tech.duoraapi.trustsafety.domain.UnknownAccountException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Bloqueio entre contas (docs/adr/0015). A outra pessoa é referenciada pelo id da conta, um UUIDv7
 * opaco. Bloquear e desbloquear são ações ({@code :block}, {@code :unblock}, docs/adr/0005), as duas
 * idempotentes e sem corpo na resposta.
 */
@RestController
@Tag(name = "blocks", description = "Bloqueio entre contas")
class BlockController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_SCHEMA = "#/components/schemas/ProblemDetail";
    private static final String ACCOUNT_ID_DESCRIPTION = "Id da outra conta, como o app o recebe ao mostrar a pessoa";

    private final BlockService blocks;

    BlockController(BlockService blocks) {
        this.blocks = blocks;
    }

    @PostMapping("/api/accounts/{accountId}:block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "blockAccount", summary = "Bloqueia outra conta",
            description = "Idempotente: bloquear quem já está bloqueado também responde 204 e mantém a data do "
                    + "primeiro bloqueio. A resposta é a mesma se a outra pessoa tiver bloqueado você.")
    @ApiResponse(responseCode = "204", description = "A conta está bloqueada")
    @ApiResponse(responseCode = "400", description = "Bloqueio de si mesmo, ou id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    @ApiResponse(responseCode = "404", description = "Não há conta com esse id",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    void block(AccountId caller,
            @Parameter(description = ACCOUNT_ID_DESCRIPTION,
                    schema = @Schema(type = "string", format = "uuid", minLength = ApiSchemas.UUID_LENGTH,
                            maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID accountId) {
        blocks.block(caller, new AccountId(accountId));
    }

    @PostMapping("/api/accounts/{accountId}:unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "unblockAccount", summary = "Desfaz o próprio bloqueio de outra conta",
            description = "Idempotente: sem bloqueio, ou com id que não é de conta, também responde 204. Um "
                    + "bloqueio que a outra pessoa fez continua valendo.")
    @ApiResponse(responseCode = "204", description = "A conta não está bloqueada por quem chama")
    @ApiResponse(responseCode = "400", description = "Id que não é UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    void unblock(AccountId caller,
            @Parameter(description = ACCOUNT_ID_DESCRIPTION,
                    schema = @Schema(type = "string", format = "uuid", minLength = ApiSchemas.UUID_LENGTH,
                            maxLength = ApiSchemas.UUID_LENGTH))
            @PathVariable UUID accountId) {
        blocks.unblock(caller, new AccountId(accountId));
    }

    @GetMapping("/api/me/blocked-accounts")
    @Operation(operationId = "listBlockedAccounts", summary = "Lista quem o usuário bloqueou",
            description = "Do bloqueio mais recente para o mais antigo, paginado por cursor. A última página "
                    + "vem com nextPageToken null.")
    @ApiResponse(responseCode = "200", description = "Uma página dos bloqueios")
    @ApiResponse(responseCode = "400", description = "maxPageSize fora de 1 a 100, ou pageToken que a API não gerou",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_SCHEMA)))
    BlockedAccountsResponse blockedAccounts(AccountId caller,
            @Parameter(description = "Quantos bloqueios no máximo nesta página",
                    schema = @Schema(type = "integer", format = "int32", minimum = "1",
                            maximum = "" + MAX_PAGE_SIZE, defaultValue = "" + DEFAULT_PAGE_SIZE))
            @RequestParam(name = "maxPageSize", required = false) String maxPageSizeText,
            @Parameter(description = "O nextPageToken da página anterior; ausente na primeira",
                    schema = @Schema(type = "string", pattern = BlockPageToken.PATTERN,
                            maxLength = BlockPageToken.MAX_LENGTH))
            @RequestParam(required = false) String pageToken) {
        int maxPageSize = pageSizeOf(maxPageSizeText);
        var after = pageToken == null ? null : BlockPageToken.decode(pageToken);
        return BlockedAccountsResponse.of(blocks.blockedBy(caller, maxPageSize, after));
    }

    /**
     * Ausente vale o padrão. Lido como texto porque o Spring trata {@code maxPageSize=} vazio como
     * ausente, e o contrato o recusa como qualquer valor que não é inteiro.
     */
    private static int pageSizeOf(String text) {
        if (text == null) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int size = Integer.parseInt(text);
            if (size >= 1 && size <= MAX_PAGE_SIZE) {
                return size;
            }
        } catch (NumberFormatException e) {
            // cai no 400 abaixo, como um número fora da faixa
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxPageSize must be between 1 and " + MAX_PAGE_SIZE);
    }

    @ExceptionHandler(SelfBlockException.class)
    ProblemDetail handleSelfBlock(SelfBlockException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(UnknownAccountException.class)
    ProblemDetail handleUnknownAccount(UnknownAccountException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(InvalidPageTokenException.class)
    ProblemDetail handleInvalidPageToken(InvalidPageTokenException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

}
