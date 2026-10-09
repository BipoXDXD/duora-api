package bipo.tech.duoraapi.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.identity.AccountId;

class ChatKeyTest {

    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final ChatPair PAIR = ChatPair.of(
            new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a")),
            new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b")));
    private static final ChatKey ROUND_TWO = new ChatKey(EVENT, 2, PAIR);

    @Test
    void theFirstRoundIsTheLowestNumber() {
        assertThat(new ChatKey(EVENT, 1, PAIR).roundNumber()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0})
    void rejectsARoundBeforeTheFirst(int roundNumber) {
        assertThatThrownBy(() -> new ChatKey(EVENT, roundNumber, PAIR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the round number starts at 1");
    }

    @Test
    void requiresTheEventAndThePair() {
        assertThatThrownBy(() -> new ChatKey(null, 1, PAIR)).isInstanceOf(NullPointerException.class).hasMessage("eventId");
        assertThatThrownBy(() -> new ChatKey(EVENT, 1, null)).isInstanceOf(NullPointerException.class).hasMessage("pair");
    }

    @Test
    void theRoundIsCurrentWhileItIsTheLatestStarted() {
        assertThat(ROUND_TWO.isLatestRound(OptionalInt.of(2))).isTrue();
    }

    /** A rodada seguinte fecha o chat; uma anterior nunca vem do matching, mas também não é a desta chave. */
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void anotherLatestRoundMeansTheRoundIsNotCurrent(int latest) {
        assertThat(ROUND_TWO.isLatestRound(OptionalInt.of(latest))).isFalse();
    }

    @Test
    void withoutAnyStartedRoundNoRoundIsCurrent() {
        assertThat(ROUND_TWO.isLatestRound(OptionalInt.empty())).isFalse();
    }

}
