package bipo.tech.duoraapi.connections.application;

import java.util.Objects;

import bipo.tech.duoraapi.connections.domain.Decision;

/**
 * A decisão de quem chama e se ela foi gravada agora ou já existia. Nunca diz nada da decisão do par nem se
 * a conexão se formou (docs/adr/0019).
 */
public record DecisionOutcome(Decision decision, boolean created) {

    public DecisionOutcome {
        Objects.requireNonNull(decision, "decision");
    }

}
