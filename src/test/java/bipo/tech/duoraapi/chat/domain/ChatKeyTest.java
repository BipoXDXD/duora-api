package bipo.tech.duoraapi.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

}
