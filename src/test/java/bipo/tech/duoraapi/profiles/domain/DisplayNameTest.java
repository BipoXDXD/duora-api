package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DisplayNameTest {

    @Test
    void stripsSurroundingSpaces() {
        assertThat(new DisplayName("  Ana Souza ").value()).isEqualTo("Ana Souza");
    }

    /** "é" escrito como "e" + acento combinante vira um caractere só, como o "é" pronto. */
    @Test
    void normalizesToComposedForm() {
        assertThat(new DisplayName("José")).isEqualTo(new DisplayName("José"));
    }

    @Test
    void acceptsOneCharacter() {
        assertThat(new DisplayName("A").value()).isEqualTo("A");
    }

    @Test
    void acceptsFiftyCharacters() {
        assertThat(new DisplayName("a".repeat(50)).value()).hasSize(50);
    }

    @Test
    void rejectsFiftyOneCharacters() {
        assertThatThrownBy(() -> new DisplayName("a".repeat(51)))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("displayName must have 1 to 50 characters");
    }

    /** O limite conta caracteres, não unidades UTF-16: um emoji fora do BMP vale um. */
    @Test
    void countsEmojiOutsideBasicPlaneAsOneCharacter() {
        var name = "😀".repeat(50);

        assertThat(new DisplayName(name).value()).isEqualTo(name);
    }

    /** O ZWJ une emojis compostos (casal, família); sem ele, o emoji se desmonta. */
    @Test
    void acceptsEmojiJoinedByZeroWidthJoiner() {
        var couple = "Ana 👩‍❤️‍👨";

        assertThat(new DisplayName(couple).value()).isEqualTo(couple);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void rejectsMissingOrBlankName(String value) {
        assertThatThrownBy(() -> new DisplayName(value)).isInstanceOf(InvalidProfileException.class);
    }

    /**
     * NUL (o PostgreSQL recusa), controle, quebra de linha, espaços que não são o comum, invisíveis
     * (zero-width) e controles de direção (U+202E inverte o texto na tela, para se passar por outro nome).
     */
    @ParameterizedTest
    @ValueSource(strings = {"Ana\u0000", "A\u0007na", "Ana\nSouza", "Ana Souza", "Ana​Souza",
            "Ana‮azuoS", "Ana Souza", "Ana　Souza", "﻿Ana"})
    void rejectsControlAndInvisibleCharacters(String value) {
        assertThatThrownBy(() -> new DisplayName(value))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("displayName contains a forbidden character");
    }

    @Test
    void doesNotExposeTheNameInToString() {
        assertThat(new DisplayName("Ana Souza").toString()).doesNotContain("Ana");
    }

}
