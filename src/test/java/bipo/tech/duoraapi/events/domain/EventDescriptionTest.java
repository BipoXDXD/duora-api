package bipo.tech.duoraapi.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventDescriptionTest {

    @Test
    void keepsLineBreaksBetweenParagraphs() {
        assertThat(new EventDescription("Jogos de tabuleiro.\n\nTraga um amigo.").value())
                .isEqualTo("Jogos de tabuleiro.\n\nTraga um amigo.");
    }

    @Test
    void normalizesWindowsLineBreaks() {
        assertThat(new EventDescription("linha 1\r\nlinha 2").value()).isEqualTo("linha 1\nlinha 2");
    }

    @Test
    void acceptsFiveHundredCharacters() {
        assertThat(new EventDescription("a".repeat(500)).value()).hasSize(500);
    }

    @Test
    void rejectsFiveHundredAndOneCharacters() {
        assertThatThrownBy(() -> new EventDescription("a".repeat(501)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("description must have 1 to 500 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n "})
    void rejectsBlankDescription(String description) {
        assertThatThrownBy(() -> new EventDescription(description))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("description must have 1 to 500 characters");
    }

    @Test
    void rejectsMissingDescription() {
        assertThatThrownBy(() -> new EventDescription(null))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("description is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oi\u0000", "o\u0007i", "o\ti", "oi​tchau", "oi‮tchau"})
    void rejectsControlAndInvisibleCharacters(String description) {
        assertThatThrownBy(() -> new EventDescription(description))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("description contains a forbidden character");
    }

}
