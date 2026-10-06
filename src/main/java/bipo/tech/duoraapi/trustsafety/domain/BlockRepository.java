package bipo.tech.duoraapi.trustsafety.domain;

import java.util.List;

import bipo.tech.duoraapi.identity.AccountId;

/** Os bloqueios gravados. Cada operação é atômica por si: nenhuma "consulta e depois grava". */
public interface BlockRepository {

    /**
     * Grava o bloqueio, se o par ainda não tiver um. Com o par já bloqueado, inclusive por um pedido
     * simultâneo, não faz nada e mantém a data do primeiro.
     *
     * @throws UnknownAccountException se a conta bloqueada não existe
     */
    void addIfAbsent(Block block);

    /** Apaga o bloqueio de {@code blocker} sobre {@code blocked}, se houver; o do outro lado fica. */
    void remove(AccountId blocker, AccountId blocked);

    /** Se uma das contas bloqueou a outra, em qualquer direção. */
    boolean existsEitherWay(AccountId first, AccountId second);

    /** Os bloqueios de {@code blocker}, do mais recente para o mais antigo, até {@code limit}. */
    List<Block> findFirstByBlocker(AccountId blocker, int limit);

    /** Como {@link #findFirstByBlocker}, começando logo depois de {@code after}. */
    List<Block> findByBlockerAfter(AccountId blocker, BlockPosition after, int limit);

}
