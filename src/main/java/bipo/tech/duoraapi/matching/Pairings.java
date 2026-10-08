package bipo.tech.duoraapi.matching;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.matching.domain.RoundNumber;
import bipo.tech.duoraapi.matching.domain.RoundRepository;
import bipo.tech.duoraapi.matching.domain.Seat;

/**
 * API publicada do matching para os outros módulos (docs/adr/0019, 0021): com quem uma pessoa formou par numa
 * rodada e qual é a última rodada do evento, sem que outro módulo leia as tabelas daqui. Os assentos de uma
 * rodada não mudam depois do sorteio, então o par vale para o resto da transação de quem pergunta.
 */
@Service
public class Pairings {

    /** O primeiro e o último número de rodada possíveis num evento, para quem valida a entrada. */
    public static final int FIRST_ROUND = RoundNumber.FIRST;
    public static final int LAST_ROUND = RoundNumber.MAX;

    private final RoundRepository rounds;

    Pairings(RoundRepository rounds) {
        this.rounds = rounds;
    }

    /**
     * O par da pessoa na rodada; vazio se ela ficou de fora, não estava no sorteio ou a rodada não existe.
     *
     * @throws IllegalArgumentException se o número está fora de {@link #FIRST_ROUND} a {@link #LAST_ROUND}:
     *         quem chama valida a entrada antes
     */
    @Transactional(readOnly = true)
    public Optional<AccountId> partnerOf(UUID eventId, int roundNumber, AccountId account) {
        if (roundNumber < FIRST_ROUND || roundNumber > LAST_ROUND) {
            throw new IllegalArgumentException("the round number must be between " + FIRST_ROUND + " and " + LAST_ROUND);
        }
        return rounds.findSeat(eventId, new RoundNumber(roundNumber), account)
                .flatMap(seat -> switch (seat) {
                    case Seat.Paired paired -> Optional.of(paired.partner());
                    case Seat.SittingOut _ -> Optional.<AccountId>empty();
                });
    }

    /**
     * O número da última rodada iniciada no evento; vazio se nenhuma começou ou o evento não existe. O chat de
     * uma rodada fecha para envio quando a seguinte começa (docs/adr/0021).
     */
    @Transactional(readOnly = true)
    public OptionalInt latestRoundOf(UUID eventId) {
        return rounds.findLatestNumber(eventId)
                .map(number -> OptionalInt.of(number.value()))
                .orElseGet(OptionalInt::empty);
    }

}
