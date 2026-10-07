package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileChangesTest {

    /** Campo ausente no pedido é keep(), nunca null: null aqui seria um quinto estado que ninguém trata. */
    @ParameterizedTest
    @ValueSource(strings = {"displayName", "birthDate", "bio", "region"})
    void everyFieldNeedsAChange(String missingField) {
        FieldChange<DisplayName> displayName = "displayName".equals(missingField) ? null : FieldChange.keep();
        FieldChange<LocalDate> birthDate = "birthDate".equals(missingField) ? null : FieldChange.keep();
        FieldChange<Bio> bio = "bio".equals(missingField) ? null : FieldChange.keep();
        FieldChange<Region> region = "region".equals(missingField) ? null : FieldChange.keep();

        assertThatThrownBy(() -> new ProfileChanges(displayName, birthDate, bio, region))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(missingField);
    }

}
