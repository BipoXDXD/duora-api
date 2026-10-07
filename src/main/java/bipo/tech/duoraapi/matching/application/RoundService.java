package bipo.tech.duoraapi.matching.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bipo.tech.duoraapi.events.EventRoster;
import bipo.tech.duoraapi.events.Roster;
import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.matching.domain.Pair;
import bipo.tech.duoraapi.matching.domain.Round;
import bipo.tech.duoraapi.matching.domain.RoundNumber;
import bipo.tech.duoraapi.matching.domain.RoundPairing;
import bipo.tech.duoraapi.matching.domain.RoundRepository;
import bipo.tech.duoraapi.matching.domain.RoundSummary;
import bipo.tech.duoraapi.matching.domain.Seat;
import bipo.tech.duoraapi.trustsafety.Blocking;

/**
 * Iniciar uma rodada de um evento e consultar o próprio lugar nela (docs/adr/0017). Inscritos e bloqueios
 * chegam pelas APIs publicadas de events e trustsafety; as rodadas são deste módulo.
 */
@Service
public class RoundService {

    private final EventRoster eventRoster;
    private final Blocking blocking;
    private final RoundRepository rounds;
    private final Clock clock;

    public RoundService(EventRoster eventRoster, Blocking blocking, RoundRepository rounds, Clock clock) {
        this.eventRoster = eventRoster;
        this.blocking = blocking;
        this.rounds = rounds;
        this.clock = clock;
    }

    /**
     * Inicia a rodada, ou devolve a que já existe com esse número. Duas chamadas simultâneas criam uma
     * rodada só: a chave primária faz a segunda esperar a primeira, e só quem gravou a rodada sorteia.
     *
     * @throws UnknownEventException se o evento não existe
     * @throws EventNotUnderwayException se o evento não está em andamento e a rodada não existe
     * @throws bipo.tech.duoraapi.matching.domain.RoundOutOfSequenceException se a rodada anterior não existe
     */
    @Transactional
    public RoundOutcome start(UUID eventId, RoundNumber number) {
        Instant now = clock.instant();
        return switch (eventRoster.rosterOf(eventId, now)) {
            case Roster.UnknownEvent unknown -> throw new UnknownEventException();
            case Roster.NotUnderway notUnderway -> rounds.findSummary(eventId, number)
                    .map(RoundOutcome::existing)
                    .orElseThrow(EventNotUnderwayException::new);
            case Roster.Underway underway -> startWhileUnderway(eventId, number, underway.registrants(), now);
        };
    }

    /** @throws RoundNotFoundException se o evento não tem rodada com esse número */
    @Transactional(readOnly = true)
    public RoundSummary find(UUID eventId, RoundNumber number) {
        return rounds.findSummary(eventId, number).orElseThrow(RoundNotFoundException::new);
    }

    /** @throws SeatNotFoundException se a rodada não existe ou a pessoa não estava nela */
    @Transactional(readOnly = true)
    public Seat seatOf(UUID eventId, RoundNumber number, AccountId account) {
        return rounds.findSeat(eventId, number, account).orElseThrow(SeatNotFoundException::new);
    }

    private RoundOutcome startWhileUnderway(UUID eventId, RoundNumber number, List<AccountId> registrants,
            Instant now) {
        var round = new Round(eventId, number, ThreadLocalRandom.current().nextLong(),
                now.truncatedTo(ChronoUnit.MICROS));
        rounds.limitLockWait();
        if (!rounds.addIfAbsent(round)) {
            return RoundOutcome.existing(rounds.findSummary(eventId, number).orElseThrow());
        }
        RoundPairing pairing = rounds.historyOf(eventId)
                .drawNextRound(registrants, blockedPairsAmong(registrants), round.seed());
        rounds.addSeats(eventId, number, pairing);
        return RoundOutcome.created(RoundSummary.of(round, pairing));
    }

    private Set<Pair> blockedPairsAmong(List<AccountId> registrants) {
        return blocking.blockedPairsAmong(registrants).stream()
                .map(blocked -> Pair.of(blocked.first(), blocked.second()))
                .collect(Collectors.toUnmodifiableSet());
    }

}
