package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class PairingHistoryTest {

    private static final long SEED = 7L;

    private static final AccountId ANA = account(1);
    private static final AccountId BIA = account(2);
    private static final AccountId CAU = account(3);
    private static final AccountId DUDA = account(4);

    @Test
    void theFirstRoundOfAnEventPairsEveryoneWhoMayMeet() {
        RoundPairing round = PairingHistory.none().drawNextRound(List.of(ANA, BIA), Set.of(), SEED);

        assertThat(round.pairs()).containsExactly(Pair.of(ANA, BIA));
    }

    @Test
    void aPairAlreadyFormedInTheEventIsNotFormedAgain() {
        PairingHistory history = new PairingHistory(Set.of(Pair.of(ANA, BIA)), Map.of());

        RoundPairing round = history.drawNextRound(List.of(ANA, BIA), Set.of(), SEED);

        assertThat(round.pairs()).isEmpty();
        assertThat(round.sittingOut()).containsExactlyInAnyOrder(ANA, BIA);
    }

    @Test
    void blockedPeopleAreNotPaired() {
        RoundPairing round = PairingHistory.none().drawNextRound(
                List.of(ANA, BIA, CAU, DUDA), Set.of(Pair.of(ANA, BIA), Pair.of(CAU, DUDA)), SEED);

        assertThat(round.pairs()).hasSize(2).doesNotContain(Pair.of(ANA, BIA), Pair.of(CAU, DUDA));
    }

    @Test
    void whoSatOutBeforeIsPairedFirst() {
        PairingHistory history = new PairingHistory(Set.of(), Map.of(CAU, 1));

        for (long seed = 0; seed < 10; seed++) {
            RoundPairing round = history.drawNextRound(List.of(ANA, BIA, CAU), Set.of(), seed);

            assertThat(round.sittingOut()).doesNotContain(CAU);
        }
    }

    @Test
    void someoneWithoutHistoryNeverSatOut() {
        assertThat(PairingHistory.none().roundsSatOut(ANA)).isZero();
    }

    private static AccountId account(int number) {
        return new AccountId(new UUID(0L, number));
    }

}
