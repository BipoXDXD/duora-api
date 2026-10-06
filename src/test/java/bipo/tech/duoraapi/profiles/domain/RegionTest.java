package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RegionTest {

    @Test
    void parsesIsoCodeOfABrazilianState() {
        assertThat(Region.fromCode("BR-SP")).isEqualTo(Region.SP);
    }

    @Test
    void exposesTheIsoCode() {
        assertThat(Region.DF.code()).isEqualTo("BR-DF");
    }

    @Test
    void coversTheTwentySixStatesAndTheFederalDistrict() {
        assertThat(Region.values()).hasSize(27);
    }

    /** Só a lista fechada: nada de texto livre, que poderia trazer endereço ou localização precisa. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "SP", "br-sp", "BR-XX", " BR-SP", "BR-SP' OR '1'='1", "Avenida Paulista, 1000"})
    void rejectsAnythingOutsideTheStateCodes(String code) {
        assertThatThrownBy(() -> Region.fromCode(code))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("region must be the ISO 3166-2 code of a Brazilian state, like BR-SP");
    }

}
