package bipo.tech.duoraapi.trustsafety;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Duas contas separadas por um bloqueio, sem dizer quem bloqueou quem (docs/adr/0015): {@link #of} põe as
 * duas na ordem do texto dos ids, que é a ordem do tipo {@code uuid} no PostgreSQL.
 */
public record BlockedPair(AccountId first, AccountId second) {

    public BlockedPair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a blocked pair needs two different accounts");
        }
        if (comesBefore(second, first)) {
            throw new IllegalArgumentException("first must come before second; use BlockedPair.of");
        }
    }

    public static BlockedPair of(AccountId one, AccountId other) {
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        return comesBefore(other, one) ? new BlockedPair(other, one) : new BlockedPair(one, other);
    }

    private static boolean comesBefore(AccountId one, AccountId other) {
        return one.value().toString().compareTo(other.value().toString()) < 0;
    }

}
