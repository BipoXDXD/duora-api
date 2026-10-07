package bipo.tech.duoraapi.matching.domain;

import java.util.Objects;

/** Uma rodada com quantos pares se formaram e quantas pessoas ficaram de fora, sem dizer quem. */
public record RoundSummary(Round round, int pairCount, int sittingOutCount) {

    public RoundSummary {
        Objects.requireNonNull(round, "round");
        if (pairCount < 0 || sittingOutCount < 0) {
            throw new IllegalArgumentException("counts cannot be negative");
        }
    }

    public static RoundSummary of(Round round, RoundPairing pairing) {
        return new RoundSummary(round, pairing.pairs().size(), pairing.sittingOut().size());
    }

}
