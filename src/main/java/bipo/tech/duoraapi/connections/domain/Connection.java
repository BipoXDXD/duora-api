package bipo.tech.duoraapi.connections.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Duas pessoas que disseram sim uma à outra (docs/adr/0019). Uma por par, qualquer que seja o evento: a
 * chave é o par normalizado.
 */
public record Connection(ConnectionPair pair, Instant connectedAt) {

    public Connection {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(connectedAt, "connectedAt");
    }

    /**
     * A conexão que a decisão mais recente forma, se formar: as duas pessoas disseram sim uma à outra na
     * mesma rodada e nenhum bloqueio as separa. A data é a da decisão que completou o interesse mútuo.
     *
     * @param partnersDecision a decisão do par sobre quem decidiu por último, se ele já decidiu
     */
    public static Optional<Connection> fromDecisions(Decision latest, Optional<Decision> partnersDecision,
            boolean separatedByBlock) {
        boolean mutualInterest = latest.interested() && partnersDecision
                .filter(latest::answers)
                .map(Decision::interested)
                .orElse(false);
        if (!mutualInterest || separatedByBlock) {
            return Optional.empty();
        }
        return Optional.of(new Connection(ConnectionPair.of(latest.decider(), latest.partner()), latest.decidedAt()));
    }

    /** A conexão do ponto de vista de um dos lados. */
    public ConnectedAccount otherThan(AccountId account) {
        return new ConnectedAccount(pair.otherThan(account), connectedAt);
    }

}
