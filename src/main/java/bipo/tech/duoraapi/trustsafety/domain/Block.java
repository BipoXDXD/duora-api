package bipo.tech.duoraapi.trustsafety.domain;

import java.time.Instant;
import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Uma conta bloqueou outra. O bloqueio tem direção: cada lado bloqueia e desbloqueia só o próprio, e
 * quem foi bloqueado não fica sabendo. Para os outros módulos, basta um bloqueio em qualquer direção
 * para separar as duas pessoas ({@code trustsafety.Blocking}).
 */
public record Block(AccountId blocker, AccountId blocked, Instant blockedAt) {

    public Block {
        Objects.requireNonNull(blocker, "blocker");
        Objects.requireNonNull(blocked, "blocked");
        Objects.requireNonNull(blockedAt, "blockedAt");
        if (blocker.equals(blocked)) {
            throw new SelfBlockException();
        }
    }

}
