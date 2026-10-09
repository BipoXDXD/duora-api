package bipo.tech.duoraapi.trustsafety;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Duas contas separadas por um bloqueio, sem dizer quem bloqueou quem (docs/adr/0015): {@link #of} põe as
 * duas na ordem de {@link AccountId#compareTo}, a mesma do banco.
 */
public record BlockedPair(AccountId first, AccountId second) {

    public BlockedPair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a blocked pair needs two different accounts");
        }
        if (second.compareTo(first) < 0) {
            throw new IllegalArgumentException("first must come before second; use BlockedPair.of");
        }
    }

    public static BlockedPair of(AccountId one, AccountId other) {
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        return other.compareTo(one) < 0 ? new BlockedPair(other, one) : new BlockedPair(one, other);
    }

}
