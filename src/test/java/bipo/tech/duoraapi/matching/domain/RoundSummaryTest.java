package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class RoundSummaryTest {

    private static final Round ROUND = new Round(UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
            new RoundNumber(2), 42L, Instant.parse("2026-11-01T22:00:00Z"));
    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final AccountId CAIO = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000c"));
    private static final AccountId DUDA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000d"));
    private static final AccountId EDU = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000e"));

    @Test
    void countsPairsAndPeopleSittingOutWithoutNamingAnyone() {
        var pairing = new RoundPairing(List.of(Pair.of(ANA, BIA), Pair.of(CAIO, DUDA)), List.of(EDU));

        assertThat(RoundSummary.of(ROUND, pairing)).isEqualTo(new RoundSummary(ROUND, 2, 1));
    }

    @Test
    void anEmptyRoundHasNoPairsAndNobodySittingOut() {
        assertThat(RoundSummary.of(ROUND, new RoundPairing(List.of(), List.of()))).isEqualTo(new RoundSummary(ROUND, 0, 0));
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> new RoundSummary(ROUND, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoundSummary(ROUND, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

}
