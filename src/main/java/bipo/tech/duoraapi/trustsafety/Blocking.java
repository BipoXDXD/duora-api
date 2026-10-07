package bipo.tech.duoraapi.trustsafety;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.domain.BlockRepository;

/**
 * API publicada do trustsafety para os outros módulos (docs/adr/0015): se duas pessoas estão separadas
 * por um bloqueio. Chat e pareamento perguntam antes de juntar duas contas; a direção não importa, nem
 * quem bloqueou. Dentro de uma transação de quem chama, a consulta participa dela.
 */
@Component
public class Blocking {

    private final BlockRepository blocks;

    Blocking(BlockRepository blocks) {
        this.blocks = blocks;
    }

    /** Se uma das contas bloqueou a outra, em qualquer direção. */
    public boolean existsBetween(AccountId first, AccountId second) {
        return blocks.existsEitherWay(first, second);
    }

    /**
     * Os pares do grupo separados por um bloqueio, em qualquer direção, numa consulta só (docs/adr/0017): o
     * pareamento de um evento pergunta por todos os pares de uma vez, em vez de uma consulta por par.
     */
    public Set<BlockedPair> blockedPairsAmong(Collection<AccountId> accounts) {
        if (accounts.isEmpty()) {
            return Set.of();
        }
        return blocks.findAmong(accounts).stream()
                .map(block -> BlockedPair.of(block.blocker(), block.blocked()))
                .collect(Collectors.toUnmodifiableSet());
    }

}
