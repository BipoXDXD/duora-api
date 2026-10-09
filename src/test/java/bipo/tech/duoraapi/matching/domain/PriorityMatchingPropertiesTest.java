package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** A prioridade pelos menores índices contra a enumeração de todos os emparelhamentos, viável em grafos pequenos. */
class PriorityMatchingPropertiesTest {

    private static final int MAX_VERTICES = 9;
    private static final int RANDOM_GRAPHS = 1000;

    @Property(tries = RANDOM_GRAPHS)
    void coversTheSameVerticesAsTheBestOfAllMaximumMatchings(@ForAll("graphs") RandomGraph graph) {
        int[] partner = PriorityMatching.partners(graph.vertices(), graph);

        assertThat(covered(partner)).isEqualTo(bestCoveredSet(graph));
    }

    @Provide
    Arbitrary<RandomGraph> graphs() {
        return RandomGraph.withVertices(0, MAX_VERTICES, RandomGraph.MIN_EDGE_PERCENT,
                RandomGraph.MAX_EDGE_PERCENT);
    }

    private static List<Boolean> covered(int[] partner) {
        return IntStream.of(partner).mapToObj(vertex -> vertex != MaximumMatching.UNMATCHED).toList();
    }

    /** Entre todos os emparelhamentos máximos, o conjunto coberto que cobre os menores índices primeiro. */
    private static List<Boolean> bestCoveredSet(RandomGraph graph) {
        List<Matching> all = new ArrayList<>();
        enumerate(graph, 0, new boolean[graph.vertices()], 0, all);
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
    private static void enumerate(RandomGraph graph, int vertex, boolean[] covered, int pairs, List<Matching> all) {
        if (vertex == graph.vertices()) {
            all.add(new Matching(covered.clone(), pairs));
            return;
        }
        if (covered[vertex]) {
            enumerate(graph, vertex + 1, covered, pairs, all);
            return;
        }
        enumerate(graph, vertex + 1, covered, pairs, all);
        for (int other = vertex + 1; other < graph.vertices(); other++) {
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
        for (int vertex = 0; vertex < candidate.length; vertex++) {
            if (candidate[vertex] != best[vertex]) {
                return candidate[vertex];
            }
        }
        return false;
    }

}
