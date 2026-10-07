package bipo.tech.duoraapi.matching.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * O resultado do sorteio de uma rodada: quem forma par com quem e quem fica de fora (docs/adr/0017).
 *
 * <p>O sorteio forma o maior número possível de pares sem nenhum par proibido. Entre os sorteios com esse
 * máximo, cobre primeiro quem ficou de fora mais vezes; empates são decididos pela semente. É uma função pura:
 * os mesmos candidatos, os mesmos pares proibidos e a mesma semente dão sempre a mesma rodada, em qualquer
 * ordem que os candidatos cheguem.
 */
public record RoundPairing(List<Pair> pairs, List<AccountId> sittingOut) {

    private static final Comparator<Candidate> MOST_ROUNDS_SAT_OUT_FIRST =
            Comparator.comparingInt(Candidate::roundsSatOut).reversed();

    private static final Comparator<Candidate> BY_ACCOUNT =
            Comparator.comparing(candidate -> candidate.account().value().toString());

    public RoundPairing {
        pairs = List.copyOf(pairs);
        sittingOut = List.copyOf(sittingOut);
    }

    /**
     * @param forbidden pares que não podem se formar (bloqueios e pares que já se encontraram no evento); pares
     *                  com alguém que não é candidato não mudam nada
     * @param seed      desempata quem tem a mesma prioridade e varia os pares entre rodadas
     */
    public static RoundPairing draw(Collection<Candidate> candidates, Set<Pair> forbidden, long seed) {
        Objects.requireNonNull(forbidden, "forbidden");
        List<AccountId> priorityOrder = priorityOrder(candidates, seed);
        int[] partner = PriorityMatching.partners(priorityOrder.size(),
                (one, other) -> !forbidden.contains(Pair.of(priorityOrder.get(one), priorityOrder.get(other))));

        List<Pair> pairs = new ArrayList<>();
        List<AccountId> sittingOut = new ArrayList<>();
        for (int person = 0; person < partner.length; person++) {
            if (partner[person] == MaximumMatching.UNMATCHED) {
                sittingOut.add(priorityOrder.get(person));
            } else if (person < partner[person]) {
                pairs.add(Pair.of(priorityOrder.get(person), priorityOrder.get(partner[person])));
            }
        }
        return new RoundPairing(pairs, sittingOut);
    }

    /**
     * Quem ficou de fora mais vezes vem antes; entre iguais, a ordem é embaralhada pela semente. A ordem dos ids
     * antes do embaralhamento torna o resultado independente da ordem de chegada dos candidatos.
     */
    private static List<AccountId> priorityOrder(Collection<Candidate> candidates, long seed) {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(BY_ACCOUNT);
        if (hasRepeatedAccount(ordered)) {
            throw new IllegalArgumentException("an account appears more than once among the candidates");
        }
        Collections.shuffle(ordered, new Random(seed));
        ordered.sort(MOST_ROUNDS_SAT_OUT_FIRST);
        return ordered.stream().map(Candidate::account).toList();
    }

    private static boolean hasRepeatedAccount(List<Candidate> orderedByAccount) {
        for (int i = 1; i < orderedByAccount.size(); i++) {
            if (orderedByAccount.get(i).account().equals(orderedByAccount.get(i - 1).account())) {
                return true;
            }
        }
        return false;
    }

}
