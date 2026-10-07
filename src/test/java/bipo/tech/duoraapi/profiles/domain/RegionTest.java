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

    /** A lista ISO 3166-2:BR escrita à mão: um código trocado na enum não passa. */
    @ParameterizedTest
    @ValueSource(strings = {"BR-AC", "BR-AL", "BR-AP", "BR-AM", "BR-BA", "BR-CE", "BR-DF", "BR-ES", "BR-GO",
            "BR-MA", "BR-MT", "BR-MS", "BR-MG", "BR-PA", "BR-PB", "BR-PR", "BR-PE", "BR-PI", "BR-RJ", "BR-RN",
            "BR-RS", "BR-RO", "BR-RR", "BR-SC", "BR-SP", "BR-SE", "BR-TO"})
    void everyStateCodeParsesBackToItself(String code) {
        assertThat(Region.fromCode(code).code()).isEqualTo(code);
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
