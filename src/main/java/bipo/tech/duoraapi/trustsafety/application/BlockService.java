package bipo.tech.duoraapi.trustsafety.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.domain.Block;
import bipo.tech.duoraapi.trustsafety.domain.BlockPosition;
import bipo.tech.duoraapi.trustsafety.domain.BlockRepository;

/**
 * Bloquear, desbloquear e listar os próprios bloqueios. Quem chama passa a conta autenticada. Cada
 * operação é um comando só no banco, então não abre transação própria.
 */
@Service
public class BlockService {

    private final BlockRepository repository;
    private final Clock clock;

    public BlockService(BlockRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Idempotente: bloquear quem já está bloqueado é sucesso.
     *
     * @throws bipo.tech.duoraapi.trustsafety.domain.SelfBlockException se as contas forem a mesma
     * @throws bipo.tech.duoraapi.trustsafety.domain.UnknownAccountException se a conta bloqueada não existe
     */
    public void block(AccountId blocker, AccountId blocked) {
        repository.addIfAbsent(new Block(blocker, blocked, clock.instant()));
    }

    /** Desbloquear quem não está bloqueado, ou uma conta que não existe, já deixa o estado pedido: é sucesso. */
    public void unblock(AccountId blocker, AccountId blocked) {
        repository.remove(blocker, blocked);
    }

    /**
     * @param maxPageSize quantos bloqueios no máximo, já validado na fronteira
     * @param after onde a página começa, ou null na primeira
     */
    public BlockedAccountsPage blockedBy(AccountId blocker, int maxPageSize, BlockPosition after) {
        int oneMoreToSeeIfThereIsANextPage = maxPageSize + 1;
        List<Block> blocks = after == null
                ? repository.findFirstByBlocker(blocker, oneMoreToSeeIfThereIsANextPage)
                : repository.findByBlockerAfter(blocker, after, oneMoreToSeeIfThereIsANextPage);
        if (blocks.size() <= maxPageSize) {
            return new BlockedAccountsPage(blocks, null);
        }
        List<Block> page = blocks.subList(0, maxPageSize);
        return new BlockedAccountsPage(page, BlockPosition.of(page.getLast()));
    }

}
