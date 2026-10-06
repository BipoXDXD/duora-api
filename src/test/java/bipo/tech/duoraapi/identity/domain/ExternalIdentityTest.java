package bipo.tech.duoraapi.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalIdentityTest {

    private static final String ISSUER = "https://tenant-id.ciamlogin.com/tenant-id/v2.0";
    private static final String OBJECT_ID = "6f1c2b9e-4d1a-4b7e-9c3f-2a1d0e9b8c7a";

    @Test
    void keepsIssuerAndSubjectExactly() {
        var identity = new ExternalIdentity(ISSUER, OBJECT_ID);

        assertThat(identity.issuer()).isEqualTo(ISSUER);
        assertThat(identity.subject()).isEqualTo(OBJECT_ID);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rejectsMissingIssuer(String issuer) {
        assertThatThrownBy(() -> new ExternalIdentity(issuer, OBJECT_ID)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rejectsMissingSubject(String subject) {
        assertThatThrownBy(() -> new ExternalIdentity(ISSUER, subject)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSubjectAtMaximumLength() {
        assertThat(new ExternalIdentity(ISSUER, "s".repeat(255)).subject()).hasSize(255);
    }

    @Test
    void rejectsSubjectAboveMaximumLength() {
        assertThatThrownBy(() -> new ExternalIdentity(ISSUER, "s".repeat(256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsIssuerAtMaximumLength() {
        assertThat(new ExternalIdentity("i".repeat(2048), OBJECT_ID).issuer()).hasSize(2048);
    }

    @Test
    void rejectsIssuerAboveMaximumLength() {
        assertThatThrownBy(() -> new ExternalIdentity("i".repeat(2049), OBJECT_ID)).isInstanceOf(IllegalArgumentException.class);
    }

}
