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

/**
 * Bloqueio entre contas (docs/adr/0015). A outra pessoa é referenciada pelo id da conta, um UUIDv7
 * opaco. Bloquear e desbloquear são ações ({@code :block}, {@code :unblock}, docs/adr/0005), as duas
 * idempotentes e sem corpo na resposta.
 */
@RestController
class BlockController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final BlockService blocks;

    BlockController(BlockService blocks) {
        this.blocks = blocks;
    }

    @PostMapping("/api/accounts/{accountId}:block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void block(AccountId caller, @PathVariable UUID accountId) {
        blocks.block(caller, new AccountId(accountId));
    }

    @PostMapping("/api/accounts/{accountId}:unblock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unblock(AccountId caller, @PathVariable UUID accountId) {
        blocks.unblock(caller, new AccountId(accountId));
    }

    @GetMapping("/api/me/blocked-accounts")
    BlockedAccountsResponse blockedAccounts(AccountId caller,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int maxPageSize,
            @RequestParam(required = false) String pageToken) {
        if (maxPageSize < 1 || maxPageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxPageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        var after = pageToken == null ? null : BlockPageToken.decode(pageToken);
        return BlockedAccountsResponse.of(blocks.blockedBy(caller, maxPageSize, after));
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
