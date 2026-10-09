package bipo.tech.duoraapi.matching.domain;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Duas pessoas que se encontram numa rodada, ou que não podem se encontrar. O par não tem direção:
 * {@link #of} põe as duas na ordem dos ids, então Ana com Bia é o mesmo par que Bia com Ana.
 * A ordem é a de {@link AccountId#compareTo}, a mesma do banco.
 */
public record Pair(AccountId first, AccountId second) {

    public Pair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("a pair needs two different people");
        }
        if (second.compareTo(first) < 0) {
            throw new IllegalArgumentException("first must come before second; use Pair.of");
        }
    }

    public static Pair of(AccountId one, AccountId other) {
        Objects.requireNonNull(one, "one");
        Objects.requireNonNull(other, "other");
        return other.compareTo(one) < 0 ? new Pair(other, one) : new Pair(one, other);
    }

}
