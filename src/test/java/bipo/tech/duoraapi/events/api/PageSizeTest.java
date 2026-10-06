package bipo.tech.duoraapi.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageSizeTest {

    @Test
    void defaultsToTenWhenAbsent() {
        assertThat(PageSize.of(null)).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void acceptsTheLimits(int pageSize) {
        assertThat(PageSize.of(pageSize)).isEqualTo(pageSize);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 51, Integer.MAX_VALUE})
    void rejectsOutsideTheLimits(int pageSize) {
        assertThatThrownBy(() -> PageSize.of(pageSize))
                .isInstanceOf(InvalidPageRequestException.class)
                .hasMessage("pageSize must be between 1 and 50");
    }

}
