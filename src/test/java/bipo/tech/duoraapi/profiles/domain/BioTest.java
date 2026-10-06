package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BioTest {

    @Test
    void keepsLineBreaksBetweenParagraphs() {
        assertThat(Bio.fromText("Gosto de trilhas.\n\nE de jogos de tabuleiro."))
                .map(Bio::value)
                .hasValue("Gosto de trilhas.\n\nE de jogos de tabuleiro.");
    }

    @Test
    void normalizesWindowsLineBreaks() {
        assertThat(Bio.fromText("linha 1\r\nlinha 2")).map(Bio::value).hasValue("linha 1\nlinha 2");
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(Bio.fromText("\n  Oi!  \n")).map(Bio::value).hasValue("Oi!");
    }

    /** Apagar o texto no formulário envia "": é o mesmo que não ter bio. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\n"})
    void blankTextIsNoBio(String text) {
        assertThat(Bio.fromText(text)).isEmpty();
    }

    @Test
    void acceptsThreeHundredCharacters() {
        assertThat(Bio.fromText("a".repeat(300))).map(Bio::value).hasValueSatisfying(value -> assertThat(value).hasSize(300));
    }

    @Test
    void rejectsThreeHundredAndOneCharacters() {
        assertThatThrownBy(() -> Bio.fromText("a".repeat(301)))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("bio must have at most 300 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oi\u0000", "o\u0007i", "o\ti", "oi tchau", "oi​tchau", "oi‮tchau",
            "oi tchau"})
    void rejectsControlAndInvisibleCharacters(String text) {
        assertThatThrownBy(() -> Bio.fromText(text))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("bio contains a forbidden character");
    }

    @Test
    void doesNotExposeTheTextInToString() {
        assertThat(Bio.fromText("Gosto de trilhas")).hasValueSatisfying(
                bio -> assertThat(bio.toString()).doesNotContain("trilhas"));
    }

}
