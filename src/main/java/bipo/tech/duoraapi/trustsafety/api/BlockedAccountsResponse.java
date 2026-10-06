package bipo.tech.duoraapi.trustsafety.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import bipo.tech.duoraapi.trustsafety.application.BlockedAccountsPage;

/**
 * Envelope de lista da docs/adr/0005. Cada item é só a referência e a data: o resumo da pessoa (nome
 * de exibição) depende de uma API publicada do profiles, que ainda não existe (docs/adr/0015).
 *
 * @param nextPageToken null na última página
 */
record BlockedAccountsResponse(List<Item> items, String nextPageToken) {

    record Item(UUID accountId, Instant blockedAt) {
    }

    static BlockedAccountsResponse of(BlockedAccountsPage page) {
        var items = page.blocks().stream()
                .map(block -> new Item(block.blocked().value(), block.blockedAt()))
                .toList();
        return new BlockedAccountsResponse(items, page.nextPosition().map(BlockPageToken::encode).orElse(null));
    }

}
