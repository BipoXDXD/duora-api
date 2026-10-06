package bipo.tech.duoraapi.trustsafety.domain;

import java.time.Instant;
import java.util.Objects;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Posição de um bloqueio na lista de quem bloqueou, ordenada do mais recente para o mais antigo; a
 * conta bloqueada desempata bloqueios do mesmo instante. A próxima página começa logo depois dela.
 */
public record BlockPosition(Instant blockedAt, AccountId blocked) {

    public BlockPosition {
        Objects.requireNonNull(blockedAt, "blockedAt");
        Objects.requireNonNull(blocked, "blocked");
    }

    public static BlockPosition of(Block block) {
        return new BlockPosition(block.blockedAt(), block.blocked());
    }

}
