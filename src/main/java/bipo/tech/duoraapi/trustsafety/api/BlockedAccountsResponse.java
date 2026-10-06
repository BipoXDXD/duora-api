package bipo.tech.duoraapi.trustsafety.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import bipo.tech.duoraapi.trustsafety.application.BlockedAccountsPage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Envelope de lista da docs/adr/0005. Cada item é só a referência e a data: o resumo da pessoa (nome
 * de exibição) depende de uma API publicada do profiles, que ainda não existe (docs/adr/0015).
 *
 * @param nextPageToken null na última página
 */
record BlockedAccountsResponse(
        @ArraySchema(maxItems = BlockController.MAX_PAGE_SIZE,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Bloqueios do mais recente para o mais antigo"))
        List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                pattern = BlockPageToken.PATTERN, maxLength = BlockPageToken.MAX_LENGTH,
                description = "Token da próxima página, ou null na última")
        String nextPageToken) {

    @Schema(name = "BlockedAccount")
    record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH, description = "Id da conta bloqueada")
            UUID accountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                    description = "Quando o bloqueio foi feito")
            Instant blockedAt) {
    }

    static BlockedAccountsResponse of(BlockedAccountsPage page) {
        var items = page.blocks().stream()
                .map(block -> new Item(block.blocked().value(), block.blockedAt()))
                .toList();
        return new BlockedAccountsResponse(items, page.nextPosition().map(BlockPageToken::encode).orElse(null));
    }

}
