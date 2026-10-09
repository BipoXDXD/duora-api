package bipo.tech.duoraapi.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.config.InvalidPageParameterException;

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

    @Test
    void acceptsFifty() {
        assertThat(PageSize.of("50")).isEqualTo(50);
    }

    @Test
    void rejectsFiftyOne() {
        assertThatThrownBy(() -> PageSize.of("51"))
                .isInstanceOf(InvalidPageParameterException.class)
                .hasMessage("maxPageSize must be between 1 and 50");
    }

}
