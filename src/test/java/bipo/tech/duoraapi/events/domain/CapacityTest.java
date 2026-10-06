package bipo.tech.duoraapi.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapacityTest {

    @ParameterizedTest
    @ValueSource(ints = {2, 200})
    void acceptsTheLimits(int places) {
        assertThat(new Capacity(places).places()).isEqualTo(places);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 201, Integer.MAX_VALUE})
    void rejectsOutsideTheLimits(int places) {
        assertThatThrownBy(() -> new Capacity(places))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("capacity must be between 2 and 200");
    }

    @Test
    void isFullWhenEveryPlaceIsTaken() {
        assertThat(new Capacity(2).isFilledBy(2)).isTrue();
    }

    @Test
    void isNotFullWithOnePlaceLeft() {
        assertThat(new Capacity(2).isFilledBy(1)).isFalse();
    }

}
