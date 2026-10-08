package bipo.tech.duoraapi.connections.domain;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * As duas contas de uma conexão, sem lado: {@link #of} põe as duas na ordem do texto dos ids, que é a ordem
 * do tipo {@code uuid} no PostgreSQL. É o par normalizado que a chave primária da tabela connection torna
 * único (docs/adr/0019).
 */
public record ConnectionPair(AccountId first, AccountId second) {

    public ConnectionPair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a connection needs two different accounts");
        }
        if (comesBefore(second, first)) {
            throw new IllegalArgumentException("first must come before second; use ConnectionPair.of");
        }
    }

    public static ConnectionPair of(AccountId one, AccountId other) {
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        return comesBefore(other, one) ? new ConnectionPair(other, one) : new ConnectionPair(one, other);
    }

    /** @throws IllegalArgumentException se a conta não é nenhum dos dois lados */
    public AccountId otherThan(AccountId account) {
        if (account.equals(first)) {
            return second;
        }
        if (account.equals(second)) {
            return first;
        }
        throw new IllegalArgumentException("the account is not in this pair");
    }

    private static boolean comesBefore(AccountId one, AccountId other) {
        return one.value().toString().compareTo(other.value().toString()) < 0;
    }

}
