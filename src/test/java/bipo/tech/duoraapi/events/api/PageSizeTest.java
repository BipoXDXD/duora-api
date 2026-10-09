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

    @Test
    void usesTheDefaultOfTheListWhenAbsent() {
        assertThat(PageSize.of(null, PageSize.ADMIN_DEFAULT)).isEqualTo(20);
    }

    @Test
    void ignoresTheDefaultOfTheListWhenPresent() {
        assertThat(PageSize.of("7", PageSize.ADMIN_DEFAULT)).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "50"})
    void acceptsTheLimits(String maxPageSize) {
        assertThat(PageSize.of(maxPageSize)).isEqualTo(Integer.parseInt(maxPageSize));
    }

    /** Vazio não é ausente: {@code maxPageSize=} é um valor que não é número. */
    @ParameterizedTest
    @ValueSource(strings = {"-2147483648", "-1", "0", "51", "2147483647", "2147483648", "1.5", "abc", "", " "})
    void rejectsOutsideTheLimits(String maxPageSize) {
        assertThatThrownBy(() -> PageSize.of(maxPageSize))
                .isInstanceOf(InvalidPageRequestException.class)
                .hasMessage("maxPageSize must be between 1 and 50");
    }

}
