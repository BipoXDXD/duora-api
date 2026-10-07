package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class CandidateTest {

    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));

    @Test
    void acceptsSomeoneWhoNeverSatOut() {
        assertThat(new Candidate(ANA, 0).roundsSatOut()).isZero();
    }

    @Test
    void rejectsANegativeNumberOfRoundsSatOut() {
        assertThatThrownBy(() -> new Candidate(ANA, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roundsSatOut cannot be negative");
    }

}
