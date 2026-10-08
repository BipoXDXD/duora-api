package bipo.tech.duoraapi.connections.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class DecisionTest {

    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final Instant DECIDED_AT = Instant.parse("2026-11-01T22:10:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void repeatingTheSameChoiceKeepsTheFirstDecision(boolean interested) {
        var first = new Decision(EVENT, 1, ANA, BIA, interested, DECIDED_AT);

        assertThat(first.confirm(interested)).isSameAs(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aDecisionIsFinal(boolean interested) {
        var first = new Decision(EVENT, 1, ANA, BIA, interested, DECIDED_AT);

        assertThatThrownBy(() -> first.confirm(!interested))
                .isInstanceOf(DecisionAlreadyMadeException.class)
                .hasMessage("the decision for this round was already made and cannot change");
    }

    @Test
    void nobodyDecidesAboutThemselves() {
        assertThatThrownBy(() -> new Decision(EVENT, 1, ANA, ANA, true, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a decision is about another account");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void theRoundNumberStartsAtOne(int roundNumber) {
        assertThatThrownBy(() -> new Decision(EVENT, roundNumber, ANA, BIA, true, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the round number starts at 1");
    }

    @Test
    void theFirstRoundIsValid() {
        assertThat(new Decision(EVENT, 1, ANA, BIA, true, DECIDED_AT).roundNumber()).isEqualTo(1);
    }

}
