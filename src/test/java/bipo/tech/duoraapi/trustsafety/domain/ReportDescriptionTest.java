package bipo.tech.duoraapi.trustsafety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReportDescriptionTest {

    @Test
    void keepsLineBreaksBetweenParagraphs() {
        assertThat(ReportDescription.fromText("Mandou mensagens ofensivas.\n\nDepois insistiu."))
                .map(ReportDescription::value)
                .hasValue("Mandou mensagens ofensivas.\n\nDepois insistiu.");
    }

    @Test
    void normalizesWindowsLineBreaksAndSurroundingWhitespace() {
        assertThat(ReportDescription.fromText("\n  linha 1\r\nlinha 2  \n"))
                .map(ReportDescription::value)
                .hasValue("linha 1\nlinha 2");
    }

    /** Forma composta: "é" digitado como "e" + acento combinado conta como um caractere. */
    @Test
    void composesCharactersBeforeCounting() {
        var decomposed = "é".repeat(ReportDescription.MAX_LENGTH);

        assertThat(ReportDescription.fromText(decomposed))
                .map(ReportDescription::value)
                .hasValue("é".repeat(ReportDescription.MAX_LENGTH));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\n"})
    void blankTextIsNoDescription(String text) {
        assertThat(ReportDescription.fromText(text)).isEmpty();
    }

    @Test
    void acceptsAThousandCharacters() {
        assertThat(ReportDescription.fromText("a".repeat(1000)))
                .map(ReportDescription::value)
                .hasValueSatisfying(value -> assertThat(value).hasSize(1000));
    }

    /** Em caracteres, como o length() do PostgreSQL: um emoji fora do BMP conta um, não dois. */
    @Test
    void countsCharactersAndNotUtf16Units() {
        assertThat(ReportDescription.fromText("😀".repeat(1000))).isPresent();
    }

    @Test
    void rejectsAThousandAndOneCharacters() {
        assertThatThrownBy(() -> ReportDescription.fromText("a".repeat(1001)))
                .isInstanceOf(InvalidReportException.class)
                .hasMessage("description must have at most 1000 characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oi\u0000", "o\u0007i", "o\ti", "oi tchau", "oi​tchau", "oi‮tchau",
            "oi tchau"})
    void rejectsControlAndInvisibleCharacters(String text) {
        assertThatThrownBy(() -> ReportDescription.fromText(text))
                .isInstanceOf(InvalidReportException.class)
                .hasMessage("description contains a forbidden character");
    }

    /** O ZWJ une emojis compostos (família); sem ele, o emoji se desmonta. */
    @Test
    void acceptsEmojiJoinedByZeroWidthJoiner() {
        var family = "Ele me chamou de \uD83D\uDC69\u200D\uD83D\uDC67";

        assertThat(ReportDescription.fromText(family)).map(ReportDescription::value).hasValue(family);
    }

    @Test
    void constructorRejectsMissingText() {
        assertThatThrownBy(() -> new ReportDescription(null))
                .isInstanceOf(InvalidReportException.class)
                .hasMessage("description is required");
    }

    /** Quem chega pelo construtor, e não por fromText, também não passa em branco. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\n"})
    void constructorRejectsBlankText(String text) {
        assertThatThrownBy(() -> new ReportDescription(text))
                .isInstanceOf(InvalidReportException.class)
                .hasMessage("description must not be blank");
    }

    @Test
    void doesNotExposeTheTextInToString() {
        assertThat(ReportDescription.fromText("ameaçou me encontrar")).hasValueSatisfying(
                description -> assertThat(description.toString()).doesNotContain("ameaçou"));
    }

}
