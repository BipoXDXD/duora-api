package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class PriorityMatchingTest {

    private static final int VERTICES = 9;
    private static final int RANDOM_GRAPHS = 1500;
    private static final int[] EDGE_PERCENTS = {10, 20, 35};

    @Test
    void theLowestIndexesAreCoveredFirstOnAPath() {
        // 0-1, 1-2, 2-3 e 3-4: o máximo tem 2 pares e deixa um de fora; quem fica é o último
        int[] partner = PriorityMatching.partners(5, edges(0, 1, 1, 2, 2, 3, 3, 4));

        assertThat(partner).containsExactly(1, 0, 3, 2, MaximumMatching.UNMATCHED);
    }

    @Test
    void aLowIndexIsCoveredEvenWhenTheFirstMaximumMatchingLeavesItOut() {
        // 2 é o centro de uma estrela com 0, 1 e 3: só um dos três cabe, e o menor índice tem a vez
        int[] partner = PriorityMatching.partners(4, edges(2, 0, 2, 1, 2, 3));

        assertThat(partner).containsExactly(2, MaximumMatching.UNMATCHED, 0, MaximumMatching.UNMATCHED);
    }

    @Test
    void coversTheSameVerticesAsTheBestOfAllMaximumMatchingsOnRandomGraphs() {
        Random random = new Random(20261008L);
        for (int graphNumber = 0; graphNumber < RANDOM_GRAPHS; graphNumber++) {
            int edgePercent = EDGE_PERCENTS[graphNumber % EDGE_PERCENTS.length];
            Set<Integer> allowed = new HashSet<>();
            for (int one = 0; one < VERTICES; one++) {
                for (int other = one + 1; other < VERTICES; other++) {
                    if (random.nextInt(100) < edgePercent) {
                        allowed.add(one * VERTICES + other);
                    }
                }
            }
            MaximumMatching.Compatibility graph = (one, other) -> allowed.contains(Math.min(one, other) * VERTICES + Math.max(one, other));

            int[] partner = PriorityMatching.partners(VERTICES, graph);

            assertThat(covered(partner)).as("graph number %d", graphNumber).isEqualTo(bestCoveredSet(graph));
        }
    }

    private static MaximumMatching.Compatibility edges(int... endpoints) {
        Set<Integer> allowed = new HashSet<>();
        for (int index = 0; index < endpoints.length; index += 2) {
            allowed.add(Math.min(endpoints[index], endpoints[index + 1]) * VERTICES + Math.max(endpoints[index], endpoints[index + 1]));
        }
        return (one, other) -> allowed.contains(Math.min(one, other) * VERTICES + Math.max(one, other));
    }

    private static List<Boolean> covered(int[] partner) {
        return IntStream.of(partner).mapToObj(vertex -> vertex != MaximumMatching.UNMATCHED).toList();
    }

    /** Entre todos os emparelhamentos máximos, o conjunto coberto que cobre os menores índices primeiro. */
    private static List<Boolean> bestCoveredSet(MaximumMatching.Compatibility graph) {
        List<Matching> all = new ArrayList<>();
        enumerate(graph, 0, new boolean[VERTICES], 0, all);
        int maximum = all.stream().mapToInt(Matching::pairs).max().orElseThrow();
        boolean[] best = null;
        for (Matching matching : all) {
            if (matching.pairs() == maximum && (best == null || isBetter(matching.covered(), best))) {
                best = matching.covered();
            }
        }
        List<Boolean> result = new ArrayList<>();
        for (boolean vertex : best) {
            result.add(vertex);
        }
        return result;
    }

    /** Cada vértice, em ordem, fica sem par ou se une a um vértice maior ainda livre. */
    private static void enumerate(MaximumMatching.Compatibility graph, int vertex, boolean[] covered, int pairs,
            List<Matching> all) {
        if (vertex == VERTICES) {
            all.add(new Matching(covered.clone(), pairs));
            return;
        }
        if (covered[vertex]) {
            enumerate(graph, vertex + 1, covered, pairs, all);
            return;
        }
        enumerate(graph, vertex + 1, covered, pairs, all);
        for (int other = vertex + 1; other < VERTICES; other++) {
            if (!covered[other] && graph.allows(vertex, other)) {
                covered[vertex] = true;
                covered[other] = true;
                enumerate(graph, vertex + 1, covered, pairs + 1, all);
                covered[vertex] = false;
                covered[other] = false;
            }
        }
    }

    private record Matching(boolean[] covered, int pairs) {
    }

    private static boolean isBetter(boolean[] candidate, boolean[] best) {
        for (int vertex = 0; vertex < VERTICES; vertex++) {
            if (candidate[vertex] != best[vertex]) {
                return candidate[vertex];
            }
        }
        return false;
    }

}
