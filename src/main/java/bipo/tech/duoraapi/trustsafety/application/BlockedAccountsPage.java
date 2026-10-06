package bipo.tech.duoraapi.trustsafety.application;

import java.util.List;
import java.util.Optional;

import bipo.tech.duoraapi.trustsafety.domain.Block;
import bipo.tech.duoraapi.trustsafety.domain.BlockPosition;

/**
 * Uma página da lista de quem a pessoa bloqueou.
 *
 * @param next onde começa a próxima página, ou null na última
 */
public record BlockedAccountsPage(List<Block> blocks, BlockPosition next) {

    public BlockedAccountsPage {
        blocks = List.copyOf(blocks);
    }

    public Optional<BlockPosition> nextPosition() {
        return Optional.ofNullable(next);
    }

}
