package bipo.tech.duoraapi.matching.domain;

import java.util.Optional;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * As rodadas e os assentos gravados (docs/adr/0017). A unicidade e a sequência das rodadas são do banco,
 * nunca uma consulta seguida de gravação.
 */
public interface RoundRepository {

    /**
     * Limita quanto a transação atual espera por um lock, até o fim dela. Chame antes de
     * {@link #addIfAbsent}: quem chega enquanto outra transação cria a mesma rodada espera por ela.
     */
    void limitLockWait();

    /**
     * Grava a rodada, se o evento ainda não tem uma com esse número. Se outra transação estiver criando a
     * mesma rodada, espera por ela; se ela confirmar, não grava nada.
     *
     * @return se gravou
     * @throws RoundOutOfSequenceException se a rodada anterior não existe
     */
    boolean addIfAbsent(Round round);

    Optional<RoundSummary> findSummary(UUID eventId, RoundNumber number);

    /** O maior número de rodada gravado no evento; como a sequência não tem buracos, é a última iniciada. */
    Optional<RoundNumber> findLatestNumber(UUID eventId);

    /** Os pares e as rodadas sem par de todas as rodadas já gravadas do evento. */
    PairingHistory historyOf(UUID eventId);

    /** Grava os assentos do sorteio: os dois lados de cada par e quem ficou de fora. */
    void addSeats(UUID eventId, RoundNumber number, RoundPairing pairing);

    Optional<Seat> findSeat(UUID eventId, RoundNumber number, AccountId account);

}
