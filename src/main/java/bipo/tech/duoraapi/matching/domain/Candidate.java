package bipo.tech.duoraapi.matching.domain;

import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Uma pessoa que pode entrar no sorteio de uma rodada, com quantas rodadas do mesmo evento ela já passou
 * sem par. Quem ficou de fora mais vezes tem prioridade no sorteio seguinte.
 */
public record Candidate(AccountId account, int roundsSatOut) {

    public Candidate {
        Objects.requireNonNull(account, "account");
        if (roundsSatOut < 0) {
            throw new IllegalArgumentException("roundsSatOut cannot be negative");
        }
    }

}
