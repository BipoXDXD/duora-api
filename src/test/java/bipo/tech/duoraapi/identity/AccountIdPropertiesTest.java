package bipo.tech.duoraapi.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * A ordem dos ids contra a do tipo {@code uuid} no PostgreSQL, que compara os 16 bytes sem sinal. Os geradores de
 * {@code long} do jqwik misturam 0, -1, {@code Long.MIN_VALUE} e {@code Long.MAX_VALUE}, onde a comparação com
 * sinal de {@link UUID#compareTo} discorda do banco.
 */
class AccountIdPropertiesTest {

    @Property
    void ordersIdsLikeTheDatabaseDoes(@ForAll long oneHigh, @ForAll long oneLow, @ForAll long otherHigh,
            @ForAll long otherLow) {
        AccountId one = new AccountId(new UUID(oneHigh, oneLow));
        AccountId other = new AccountId(new UUID(otherHigh, otherLow));

        assertThat(Integer.signum(one.compareTo(other))).isEqualTo(databaseOrder(oneHigh, oneLow, otherHigh, otherLow));
    }

    /** Bytes sem sinal, do mais significativo para o menos, como o {@code uuid_cmp} do PostgreSQL. */
    private static int databaseOrder(long oneHigh, long oneLow, long otherHigh, long otherLow) {
        int byHigh = Long.compareUnsigned(oneHigh, otherHigh);
        return Integer.signum(byHigh != 0 ? byHigh : Long.compareUnsigned(oneLow, otherLow));
    }

}
