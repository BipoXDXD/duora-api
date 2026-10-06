package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FieldChangeTest {

    @Test
    void mappingKeepsAKeep() {
        FieldChange<String> keep = FieldChange.keep();

        assertThat(keep.map(String::length)).isEqualTo(FieldChange.keep());
    }

    @Test
    void mappingKeepsAClear() {
        FieldChange<String> clear = FieldChange.clear();

        assertThat(clear.map(String::length)).isEqualTo(FieldChange.clear());
    }

    @Test
    void mappingConvertsTheNewValue() {
        assertThat(FieldChange.setTo("Ana").map(String::length)).isEqualTo(FieldChange.setTo(3));
    }

    @Test
    void doesNotExposeTheNewValueInToString() {
        assertThat(FieldChange.setTo("1990-05-10").toString()).doesNotContain("1990");
    }

}
