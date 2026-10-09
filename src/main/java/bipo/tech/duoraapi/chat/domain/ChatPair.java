package bipo.tech.duoraapi.chat.domain;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * As duas contas de um chat, sem lado: {@link #of} põe as duas na ordem do texto dos ids, que é a ordem do
 * tipo {@code uuid} no PostgreSQL. É o par normalizado que a chave natural da tabela chat torna único.
 */
public record ChatPair(AccountId first, AccountId second) {

    public ChatPair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a chat needs two different accounts");
        }
        if (comesBefore(second, first)) {
            throw new IllegalArgumentException("first must come before second; use ChatPair.of");
        }
    }

    public static ChatPair of(AccountId one, AccountId other) {
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        return comesBefore(other, one) ? new ChatPair(other, one) : new ChatPair(one, other);
    }

    public boolean includes(AccountId account) {
        return first.equals(account) || second.equals(account);
    }

    private static boolean comesBefore(AccountId one, AccountId other) {
        return one.value().toString().compareTo(other.value().toString()) < 0;
    }

}
