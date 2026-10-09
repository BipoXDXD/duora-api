package bipo.tech.duoraapi.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.FieldErrorCode;

class ChatMessageTextTest {

    @Test
    void keepsLineBreaks() {
        assertThat(new ChatMessageText("oi!\n\ntudo bem?").value()).isEqualTo("oi!\n\ntudo bem?");
    }

    @Test
    void normalizesWindowsLineBreaksAndSurroundingWhitespace() {
        assertThat(new ChatMessageText("\n  linha 1\r\nlinha 2  ").value()).isEqualTo("linha 1\nlinha 2");
    }

    /** "e" seguido do acento combinante vira o "é" composto: o mesmo texto, contado como um caractere. */
    @Test
    void composesTheTextBeforeCountingIt() {
        var text = new ChatMessageText("é".repeat(500));

        assertThat(text.value()).isEqualTo("é".repeat(500));
    }

    @Test
    void acceptsOneCharacter() {
        assertThat(new ChatMessageText("a").value()).isEqualTo("a");
    }

    @Test
    void acceptsFiveHundredCharacters() {
        assertThat(new ChatMessageText("a".repeat(500)).value()).hasSize(500);
    }

    @Test
    void rejectsFiveHundredAndOneCharacters() {
        assertThatThrownBy(() -> new ChatMessageText("a".repeat(501)))
                .isInstanceOfSatisfying(InvalidChatMessageException.class, exception -> {
                    assertThat(exception.field()).isEqualTo("text");
                    assertThat(exception.code()).isEqualTo(FieldErrorCode.TOO_LONG);
                })
                .hasMessage("text must have 1 to 500 characters");
    }

    /** Um emoji fora do plano básico ocupa duas unidades UTF-16, mas é um caractere. */
    @Test
    void countsCharactersAndNotUtf16Units() {
        var emoji = "😀";

        assertThat(new ChatMessageText(emoji.repeat(500)).value()).isEqualTo(emoji.repeat(500));
        assertThatThrownBy(() -> new ChatMessageText(emoji.repeat(501)))
                .isInstanceOf(InvalidChatMessageException.class);
    }

    @Test
    void rejectsMissingText() {
        assertThatThrownBy(() -> new ChatMessageText(null))
                .isInstanceOfSatisfying(InvalidChatMessageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(FieldErrorCode.REQUIRED))
                .hasMessage("text is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\n", "\r\n"})
    void rejectsBlankText(String text) {
        assertThatThrownBy(() -> new ChatMessageText(text))
                .isInstanceOfSatisfying(InvalidChatMessageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(FieldErrorCode.TOO_SHORT))
                .hasMessage("text must have 1 to 500 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oi\u0000", "o\u0007i", "o\ti", "oi tchau", "oi​tchau", "oi‮tchau",
            "oi tchau", "oi\rtchau"})
    void rejectsControlAndInvisibleCharacters(String text) {
        assertThatThrownBy(() -> new ChatMessageText(text))
                .isInstanceOfSatisfying(InvalidChatMessageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(FieldErrorCode.FORBIDDEN_CHARACTER))
                .hasMessage("text contains a forbidden character");
    }

    /** O ZWJ une emojis compostos (família); sem ele, o emoji se desmonta. */
    @Test
    void acceptsEmojiJoinedByZeroWidthJoiner() {
        var family = "👩‍👧";

        assertThat(new ChatMessageText(family).value()).isEqualTo(family);
    }

    /** Link é texto como outro qualquer: nada de âncora nem prévia (docs/adr/0021). */
    @Test
    void keepsMarkupAsPlainText() {
        assertThat(new ChatMessageText("<a href=\"https://x.test\">oi</a>").value())
                .isEqualTo("<a href=\"https://x.test\">oi</a>");
    }

    @Test
    void doesNotExposeTheTextInToString() {
        assertThat(new ChatMessageText("segredo do encontro").toString()).doesNotContain("segredo").contains("redacted");
    }

}
