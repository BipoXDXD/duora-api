package bipo.tech.duoraapi.matching.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Emparelhamento máximo que, entre todos os máximos, cobre primeiro os vértices de índice menor: o vértice 0 só
 * fica de fora se nenhum emparelhamento máximo o cobre, e assim por diante (ordem lexicográfica).
 *
 * <p>Os conjuntos de vértices que algum emparelhamento cobre formam um matroide, então o guloso pela ordem dos
 * índices acha esse conjunto: cada vértice entra se ele e os já escolhidos ainda podem ser cobertos juntos. O
 * teste "o conjunto X pode ser coberto?" vira emparelhamento perfeito num grafo auxiliar: os vértices fora de X
 * ganham uma turma de vértices extras ligados a todos eles e entre si, que absorvem quem ficar sem par. Há
 * emparelhamento perfeito no auxiliar se e só se algum emparelhamento do grafo original cobre X.
 *
 * <p>O teste caro só roda para quem está sem par na sua vez, o que costuma ser pouca gente: num evento com
 * poucos bloqueios, só quem sobra num grupo ímpar.
 */
final class PriorityMatching {

    private PriorityMatching() {
    }

    /** O par de cada vértice, ou {@link MaximumMatching#UNMATCHED}. */
    static int[] partners(int vertices, MaximumMatching.Compatibility compatibility) {
        int[] partner = MaximumMatching.partners(vertices, compatibility);
        boolean[] chosen = new boolean[vertices];
        for (int vertex = 0; vertex < vertices; vertex++) {
            if (partner[vertex] == MaximumMatching.UNMATCHED) {
                chosen[vertex] = true;
                Optional<int[]> covering = coveringChosen(vertices, compatibility, chosen);
                if (covering.isPresent()) {
                    partner = covering.get();
                } else {
                    chosen[vertex] = false;
                }
            } else {
                chosen[vertex] = true;
            }
        }
        return partner;
    }

    /** Um emparelhamento que cobre todos os escolhidos, se existe. */
    private static Optional<int[]> coveringChosen(int vertices, MaximumMatching.Compatibility compatibility, boolean[] chosen) {
        int optional = vertices - countChosen(chosen);
        int extras = (vertices + optional) % 2 == 0 ? optional : optional + 1;
        int total = vertices + extras;
        int[] auxiliary = MaximumMatching.partners(total, (one, other) -> {
            boolean oneIsExtra = one >= vertices;
            boolean otherIsExtra = other >= vertices;
            if (oneIsExtra && otherIsExtra) {
                return true;
            }
            if (oneIsExtra) {
                return !chosen[other];
            }
            if (otherIsExtra) {
                return !chosen[one];
            }
            return compatibility.allows(one, other);
        });
        if (Arrays.stream(auxiliary).anyMatch(partner -> partner == MaximumMatching.UNMATCHED)) {
            return Optional.empty();
        }
        int[] partner = Arrays.copyOf(auxiliary, vertices);
        for (int vertex = 0; vertex < vertices; vertex++) {
            if (partner[vertex] >= vertices) {
                partner[vertex] = MaximumMatching.UNMATCHED;
            }
        }
        return Optional.of(partner);
    }

    private static int countChosen(boolean[] chosen) {
        int count = 0;
        for (boolean isChosen : chosen) {
            if (isChosen) {
                count++;
            }
        }
        return count;
    }

}
