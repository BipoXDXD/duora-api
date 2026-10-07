package bipo.tech.duoraapi.matching.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * O que as rodadas anteriores de um evento deixaram: os pares que já se formaram, que não se repetem, e
 * quantas vezes cada pessoa ficou de fora, que dá prioridade no sorteio seguinte (docs/adr/0017).
 */
public record PairingHistory(Set<Pair> pairsFormed, Map<AccountId, Integer> roundsSatOutByAccount) {

    public PairingHistory {
        pairsFormed = Set.copyOf(pairsFormed);
        roundsSatOutByAccount = Map.copyOf(roundsSatOutByAccount);
    }

    /** O evento ainda não teve rodada. */
    public static PairingHistory none() {
        return new PairingHistory(Set.of(), Map.of());
    }

    public int roundsSatOut(AccountId account) {
        return roundsSatOutByAccount.getOrDefault(account, 0);
    }

    /**
     * Sorteia a próxima rodada entre os inscritos.
     *
     * @param blocked pares separados por bloqueio, em qualquer direção
     */
    public RoundPairing drawNextRound(List<AccountId> registrants, Set<Pair> blocked, long seed) {
        List<Candidate> candidates = registrants.stream()
                .map(account -> new Candidate(account, roundsSatOut(account)))
                .toList();
        Set<Pair> forbidden = new HashSet<>(pairsFormed);
        forbidden.addAll(blocked);
        return RoundPairing.draw(candidates, forbidden, seed);
    }

}
