package bipo.tech.duoraapi.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventTitleTest {

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(new EventTitle("  Noite de jogos  ").value()).isEqualTo("Noite de jogos");
    }

    @Test
    void acceptsOneCharacter() {
        assertThat(new EventTitle("a").value()).isEqualTo("a");
    }

    @Test
    void acceptsEightyCharacters() {
        assertThat(new EventTitle("a".repeat(80)).value()).hasSize(80);
    }

    @Test
    void rejectsEightyOneCharacters() {
        assertThatThrownBy(() -> new EventTitle("a".repeat(81)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("title must have 1 to 80 characters");
    }

    /** Emojis contam como um caractere, como no length() do PostgreSQL. */
    @Test
    void countsCharactersAndNotUtf16Units() {
        assertThat(new EventTitle("🎲".repeat(80)).value()).isEqualTo("🎲".repeat(80));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankTitle(String title) {
        assertThatThrownBy(() -> new EventTitle(title))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("title must have 1 to 80 characters");
    }

    @Test
    void rejectsMissingTitle() {
        assertThatThrownBy(() -> new EventTitle(null))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("title is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Noite\u0000", "Noite\nde jogos", "Noite\tde jogos", "Noite​de jogos",
            "Noite‮de jogos", "Noite de jogos"})
    void rejectsLineBreaksControlAndInvisibleCharacters(String title) {
        assertThatThrownBy(() -> new EventTitle(title))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("title contains a forbidden character");
    }

}
