package bipo.tech.duoraapi.events.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.events.domain.EventStatus;

class StatusFilterTest {

    @Test
    void absentFilterKeepsEveryStatus() {
        assertThat(StatusFilter.of(null)).isEqualTo(EnumSet.allOf(EventStatus.class));
    }

    @ParameterizedTest
    @EnumSource(EventStatus.class)
    void namedStatusKeepsOnlyThatOne(EventStatus status) {
        assertThat(StatusFilter.of(status.name())).containsExactly(status);
    }

    /** Vazio não é ausente, e a grafia é a da resposta: nada de minúscula nem de lista. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "draft", "Published", "ENDED", "DRAFT,PUBLISHED", " DRAFT", "DRAFT "})
    void rejectsAnythingElse(String status) {
        assertThatThrownBy(() -> StatusFilter.of(status))
                .isInstanceOf(InvalidPageRequestException.class)
                .hasMessage("status must be one of DRAFT, PUBLISHED, CANCELLED");
    }

}
