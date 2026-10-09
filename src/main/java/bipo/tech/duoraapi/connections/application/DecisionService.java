package bipo.tech.duoraapi.connections.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.connections.domain.Connection;
import bipo.tech.duoraapi.connections.domain.ConnectionPair;
import bipo.tech.duoraapi.connections.domain.ConnectionRepository;
import bipo.tech.duoraapi.connections.domain.Decision;
import bipo.tech.duoraapi.connections.domain.DecisionRepository;
import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.matching.Pairings;
import bipo.tech.duoraapi.trustsafety.Blocking;

/**
 * A decisão privada depois de uma rodada e a conexão por interesse mútuo (docs/adr/0019). O par vem da API
 * publicada do matching, e o bloqueio da do trustsafety.
 */
@Service
public class DecisionService {

    private final Pairings pairings;
    private final Blocking blocking;
    private final DecisionRepository decisions;
    private final ConnectionRepository connections;
    private final Clock clock;

    public DecisionService(Pairings pairings, Blocking blocking, DecisionRepository decisions,
            ConnectionRepository connections, Clock clock) {
        this.pairings = pairings;
        this.blocking = blocking;
        this.decisions = decisions;
        this.connections = connections;
        this.clock = clock;
    }

    /**
     * Grava a decisão de quem chama sobre o par da rodada, ou devolve a mesma se ela já existe. Com o "sim"
     * das duas pessoas e nenhum bloqueio entre elas, forma a conexão na mesma transação.
     *
     * <p>Depois de gravar, o caminho é o mesmo qualquer que seja a situação do par (disse não, disse sim ou
     * não decidiu): a decisão dele e o bloqueio são sempre lidos, e a resposta só fala de quem chama.
     *
     * @throws NotPairedException se quem chama não formou par nessa rodada
     * @throws bipo.tech.duoraapi.connections.domain.DecisionAlreadyMadeException se a decisão já existe com
     *         a outra escolha
     */
    @Transactional
    public DecisionOutcome decide(UUID eventId, int roundNumber, AccountId decider, boolean interested) {
        AccountId partner = pairings.partnerOf(eventId, roundNumber, decider).orElseThrow(NotPairedException::new);
        var requested = new Decision(eventId, roundNumber, decider, partner, interested,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        decisions.lockPair(eventId, roundNumber, ConnectionPair.of(decider, partner));
        if (!decisions.addIfAbsent(requested)) {
            Decision existing = decisions.findByDecider(eventId, roundNumber, decider).orElseThrow();
            return new DecisionOutcome(existing.confirm(interested), false);
        }
        var partnersDecision = decisions.findByDecider(eventId, roundNumber, partner);
        boolean separatedByBlock = blocking.existsBetween(decider, partner);
        Connection.fromDecisions(requested, partnersDecision, separatedByBlock).ifPresent(connections::addIfAbsent);
        return new DecisionOutcome(requested, true);
    }

    /** @throws DecisionNotFoundException se quem chama não decidiu nessa rodada */
    @Transactional(readOnly = true)
    public Decision decisionOf(UUID eventId, int roundNumber, AccountId decider) {
        return decisions.findByDecider(eventId, roundNumber, decider).orElseThrow(DecisionNotFoundException::new);
    }

}
