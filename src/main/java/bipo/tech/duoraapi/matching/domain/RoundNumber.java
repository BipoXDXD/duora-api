package bipo.tech.duoraapi.matching.domain;

import java.util.Optional;

/**
 * O número de uma rodada dentro do evento, a partir de 1 (docs/adr/0017). O teto de 100 dá folga para um
 * evento de até 12 horas e repete o CHECK da tabela round.
 */
public record RoundNumber(int value) {

    public static final int FIRST = 1;
    public static final int MAX = 100;

    public RoundNumber {
        if (value < FIRST || value > MAX) {
            throw new InvalidRoundNumberException("the round number must be between " + FIRST + " and " + MAX);
        }
    }

    /** A rodada que precisa existir antes desta; a primeira não tem. */
    public Optional<RoundNumber> previous() {
        return value == FIRST ? Optional.empty() : Optional.of(new RoundNumber(value - 1));
    }

}
