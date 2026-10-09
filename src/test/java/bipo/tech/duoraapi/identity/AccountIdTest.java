package bipo.tech.duoraapi.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AccountIdTest {

    private static final AccountId LOW = new AccountId(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
    private static final AccountId HIGH = new AccountId(UUID.fromString("80000000-0000-0000-0000-000000000000"));

    /** O UUID compara com sinal e poria HIGH antes de LOW; o PostgreSQL, sem sinal, põe LOW antes. */
    @Test
    void ordersIdsLikeTheDatabaseDoes() {
        assertThat(LOW.compareTo(HIGH)).isNegative();
        assertThat(HIGH.compareTo(LOW)).isPositive();
    }

    @Test
    void sameIdComparesEqual() {
        assertThat(LOW.compareTo(new AccountId(LOW.value()))).isZero();
    }

}
