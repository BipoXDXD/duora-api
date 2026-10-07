package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

class PairTest {

    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));

    @Test
    void isTheSamePairWhicheverPersonComesFirst() {
        assertThat(Pair.of(BIA, ANA)).isEqualTo(Pair.of(ANA, BIA));
    }

    @Test
    void putsThePeopleInTheOrderOfTheirIds() {
        Pair pair = Pair.of(BIA, ANA);

        assertThat(pair.first()).isEqualTo(ANA);
        assertThat(pair.second()).isEqualTo(BIA);
    }

    @Test
    void ordersIdsLikeTheDatabaseDoesAndNotByTheSignedComparisonOfUuid() {
        AccountId low = new AccountId(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
        AccountId high = new AccountId(UUID.fromString("80000000-0000-0000-0000-000000000000"));

        assertThat(Pair.of(high, low).first()).isEqualTo(low);
    }

    @Test
    void aPersonCannotBePairedWithThemselves() {
        assertThatThrownBy(() -> Pair.of(ANA, ANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a pair needs two different people");
    }

    @Test
    void rejectsPeopleOutOfOrder() {
        assertThatThrownBy(() -> new Pair(BIA, ANA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("first must come before second; use Pair.of");
    }

}
