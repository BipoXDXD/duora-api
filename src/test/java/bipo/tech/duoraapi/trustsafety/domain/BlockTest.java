package bipo.tech.duoraapi.trustsafety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class BlockTest {

    private static final AccountId ANA = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"));
    private static final AccountId BRUNO = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8c"));
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void anAccountBlocksAnother() {
        var block = new Block(ANA, BRUNO, NOW);

        assertThat(block.blocker()).isEqualTo(ANA);
        assertThat(block.blocked()).isEqualTo(BRUNO);
        assertThat(block.blockedAt()).isEqualTo(NOW);
    }

    @Test
    void anAccountCannotBlockItself() {
        assertThatThrownBy(() -> new Block(ANA, new AccountId(ANA.value()), NOW))
                .isInstanceOf(SelfBlockException.class)
                .hasMessage("an account cannot block itself");
    }

}
