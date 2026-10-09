package bipo.tech.duoraapi.connections.domain;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * As duas contas de uma conexão, sem lado: {@link #of} põe as duas na ordem de {@link AccountId#compareTo},
 * a mesma do banco. É o par normalizado que a chave primária da tabela connection torna
 * único (docs/adr/0019).
 */
public record ConnectionPair(AccountId first, AccountId second) {

    public ConnectionPair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a connection needs two different accounts");
        }
        if (second.compareTo(first) < 0) {
            throw new IllegalArgumentException("first must come before second; use ConnectionPair.of");
        }
    }

    public static ConnectionPair of(AccountId one, AccountId other) {
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        return other.compareTo(one) < 0 ? new ConnectionPair(other, one) : new ConnectionPair(one, other);
    }

}
