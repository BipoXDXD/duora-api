package bipo.tech.duoraapi.connections.domain;

import java.util.Optional;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * As decisões privadas gravadas (docs/adr/0019). A unicidade é do banco; a avaliação do interesse mútuo é
 * serializada por par e rodada, para dois "sim" simultâneos nunca deixarem de formar a conexão.
 */
public interface DecisionRepository {

    /** Limita quanto a transação atual espera por um lock, até o fim dela. Chame antes de {@link #lockPair}. */
    void limitLockWait();

    /**
     * Trava o par naquela rodada até o fim da transação: as decisões das duas pessoas sobre o mesmo par
     * passam uma de cada vez, e a segunda enxerga a primeira já confirmada.
     */
    void lockPair(UUID eventId, int roundNumber, ConnectionPair pair);

    /**
     * Grava a decisão, se a pessoa ainda não decidiu nessa rodada.
     *
     * @return se gravou
     */
    boolean addIfAbsent(Decision decision);

    Optional<Decision> findByDecider(UUID eventId, int roundNumber, AccountId decider);

}
