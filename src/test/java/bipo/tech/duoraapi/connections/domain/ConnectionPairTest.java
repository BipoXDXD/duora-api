package bipo.tech.duoraapi.connections.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class ConnectionPairTest {

    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final AccountId CAIO = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000c"));

    @Test
    void isTheSamePairWhicheverSideComesFirst() {
        assertThat(ConnectionPair.of(BIA, ANA)).isEqualTo(ConnectionPair.of(ANA, BIA));
    }

    @Test
    void ordersIdsLikeTheDatabaseDoes() {
        AccountId low = new AccountId(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
        AccountId high = new AccountId(UUID.fromString("80000000-0000-0000-0000-000000000000"));

        assertThat(ConnectionPair.of(high, low).first()).isEqualTo(low);
        assertThat(ConnectionPair.of(high, low).second()).isEqualTo(high);
    }

    @Test
    void needsTwoDifferentAccounts() {
        assertThatThrownBy(() -> ConnectionPair.of(ANA, ANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a connection needs two different accounts");
    }

    @Test
    void rejectsAccountsOutOfOrder() {
        assertThatThrownBy(() -> new ConnectionPair(BIA, ANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("first must come before second; use ConnectionPair.of");
    }

    @Test
    void theOtherSideOfEitherAccountIsTheRemainingOne() {
        var pair = ConnectionPair.of(ANA, BIA);

        assertThat(pair.otherThan(ANA)).isEqualTo(BIA);
        assertThat(pair.otherThan(BIA)).isEqualTo(ANA);
    }

    @Test
    void anAccountOutsideThePairHasNoOtherSide() {
        assertThatThrownBy(() -> ConnectionPair.of(ANA, BIA).otherThan(CAIO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the account is not in this pair");
    }

}
