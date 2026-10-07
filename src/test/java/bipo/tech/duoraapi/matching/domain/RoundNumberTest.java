package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoundNumberTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsTheLimits(int value) {
        assertThat(new RoundNumber(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void rejectsOutsideTheLimits(int value) {
        assertThatThrownBy(() -> new RoundNumber(value))
                .isInstanceOf(InvalidRoundNumberException.class)
                .hasMessage("the round number must be between 1 and 100");
    }

    @Test
    void theFirstRoundHasNoPreviousOne() {
        assertThat(new RoundNumber(1).previous()).isEmpty();
    }

    @Test
    void aLaterRoundComesRightAfterThePreviousOne() {
        assertThat(new RoundNumber(2).previous()).contains(new RoundNumber(1));
    }

}
