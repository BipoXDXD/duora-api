package bipo.tech.duoraapi.trustsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class BlockedPairTest {

    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));

    @Test
    void doesNotTellWhoBlockedWhom() {
        assertThat(BlockedPair.of(BIA, ANA)).isEqualTo(BlockedPair.of(ANA, BIA));
    }

    @Test
    void ordersIdsLikeTheDatabaseDoes() {
        AccountId low = new AccountId(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
        AccountId high = new AccountId(UUID.fromString("80000000-0000-0000-0000-000000000000"));

        assertThat(BlockedPair.of(high, low).first()).isEqualTo(low);
    }

    @Test
    void needsTwoDifferentAccounts() {
        assertThatThrownBy(() -> BlockedPair.of(ANA, ANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a blocked pair needs two different accounts");
    }

    @Test
    void rejectsAccountsOutOfOrder() {
        assertThatThrownBy(() -> new BlockedPair(BIA, ANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("first must come before second; use BlockedPair.of");
    }

}
