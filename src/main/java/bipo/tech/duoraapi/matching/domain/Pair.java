package bipo.tech.duoraapi.matching.domain;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Duas pessoas que se encontram numa rodada, ou que não podem se encontrar. O par não tem direção:
 * {@link #of} põe as duas na ordem dos ids, então Ana com Bia é o mesmo par que Bia com Ana.
 *
 * <p>A ordem é a do texto do UUID, que coincide com a ordem do tipo {@code uuid} no PostgreSQL (bytes sem
 * sinal). {@link java.util.UUID#compareTo} compara com sinal e discordaria do banco.
 */
public record Pair(AccountId first, AccountId second) {

    public Pair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a pair needs two different people");
        }
        if (comesBefore(second, first)) {
            throw new IllegalArgumentException("first must come before second; use Pair.of");
        }
    }

    public static Pair of(AccountId one, AccountId other) {
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        return comesBefore(other, one) ? new Pair(other, one) : new Pair(one, other);
    }

    private static boolean comesBefore(AccountId one, AccountId other) {
        return one.value().toString().compareTo(other.value().toString()) < 0;
    }

}
