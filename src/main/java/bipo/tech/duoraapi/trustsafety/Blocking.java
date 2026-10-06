package bipo.tech.duoraapi.trustsafety;

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

}
