package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MaxPageSizeTest {

    private static final int DEFAULT = 20;
    private static final int MAX = 50;

    @Test
    void usesTheDefaultWhenAbsent() {
        assertThat(MaxPageSize.parse(null, DEFAULT, MAX)).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "7", "50"})
    void acceptsFromOneToTheMaximum(String text) {
        assertThat(MaxPageSize.parse(text, DEFAULT, MAX)).isEqualTo(Integer.parseInt(text));
    }

    /** Vazio não é ausente: {@code maxPageSize=} é um valor que não é número. */
    @ParameterizedTest
    @ValueSource(strings = {"-2147483648", "-1", "0", "51", "2147483647", "2147483648", "1.5", "abc", "", " "})
    void rejectsAnythingElse(String text) {
        assertThatThrownBy(() -> MaxPageSize.parse(text, DEFAULT, MAX))
                .isInstanceOf(InvalidPageParameterException.class)
                .hasMessage("maxPageSize must be between 1 and 50");
    }

}
