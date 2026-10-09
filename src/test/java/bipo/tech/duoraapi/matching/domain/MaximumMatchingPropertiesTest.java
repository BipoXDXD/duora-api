package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** O emparelhamento contra uma busca exaustiva do tamanho máximo, que só é viável em grafos pequenos. */
class MaximumMatchingPropertiesTest {

    private static final int SMALL_GRAPH_VERTICES = 6;
    private static final int SMALL_GRAPH_POSSIBLE_EDGES = SMALL_GRAPH_VERTICES * (SMALL_GRAPH_VERTICES - 1) / 2;
    private static final int EVERY_SMALL_GRAPH = 1 << SMALL_GRAPH_POSSIBLE_EDGES;
    private static final int LARGE_GRAPH_VERTICES = 10;
    private static final int LARGE_GRAPHS = 1000;
    /**
     * Grafos esparsos e médios: são os que pedem caminhos de aumento por ciclos ímpares. Com densidade de 1 a 99%,
     * o blossom desligado só falhava depois de umas 450 tentativas; de 15 a 35%, em menos de 250.
     */
    private static final int SPARSE_EDGE_PERCENT = 15;
    private static final int MEDIUM_EDGE_PERCENT = 35;

    /** Todo grafo de 6 vértices (32 768): cada bit da máscara liga uma das 15 arestas possíveis. */
    @Property(generation = GenerationMode.EXHAUSTIVE, tries = EVERY_SMALL_GRAPH)
    void matchesTheSizeOfAnExhaustiveSearchOnEveryGraphWithSixVertices(
            @ForAll @IntRange(min = 0, max = EVERY_SMALL_GRAPH - 1) int edgeMask) {
        MaximumMatching.Compatibility graph = smallGraphOf(edgeMask);

        int[] partner = MaximumMatching.partners(SMALL_GRAPH_VERTICES, graph);

        assertConsistent(partner, graph);
        assertThat(matchedCount(partner) / 2).isEqualTo(exhaustiveMatchingSize(graph, SMALL_GRAPH_VERTICES, 0));
    }

    /** Grafos maiores têm blossoms aninhados, que os de 6 vértices não alcançam. */
    @Property(tries = LARGE_GRAPHS)
    void matchesTheSizeOfAnExhaustiveSearchOnRandomGraphsWithSevenToTenVertices(
            @ForAll("largeGraphs") RandomGraph graph) {
        int[] partner = MaximumMatching.partners(graph.vertices(), graph);

        assertConsistent(partner, graph);
        assertThat(matchedCount(partner) / 2).isEqualTo(exhaustiveMatchingSize(graph, graph.vertices(), 0));
    }

    @Provide
    Arbitrary<RandomGraph> largeGraphs() {
        return RandomGraph.withVertices(SMALL_GRAPH_VERTICES + 1, LARGE_GRAPH_VERTICES, SPARSE_EDGE_PERCENT,
                MEDIUM_EDGE_PERCENT);
    }

    private static MaximumMatching.Compatibility smallGraphOf(int edgeMask) {
        Set<RandomGraph.Edge> edges = new HashSet<>();
        int bit = 0;
        for (int one = 0; one < SMALL_GRAPH_VERTICES; one++) {
            for (int other = one + 1; other < SMALL_GRAPH_VERTICES; other++) {
                if ((edgeMask >> bit++ & 1) == 1) {
                    edges.add(new RandomGraph.Edge(one, other));
                }
            }
        }
        return new RandomGraph(SMALL_GRAPH_VERTICES, edges);
    }

    private static int exhaustiveMatchingSize(MaximumMatching.Compatibility graph, int vertices, int usedMask) {
        int first = IntStream.range(0, vertices).filter(vertex -> (usedMask >> vertex & 1) == 0).findFirst().orElse(-1);
        if (first == -1) {
            return 0;
        }
        int best = exhaustiveMatchingSize(graph, vertices, usedMask | 1 << first);
        for (int other = first + 1; other < vertices; other++) {
            if ((usedMask >> other & 1) == 0 && graph.allows(first, other)) {
                best = Math.max(best, 1 + exhaustiveMatchingSize(graph, vertices, usedMask | 1 << first | 1 << other));
            }
        }
        return best;
    }

    private static int matchedCount(int[] partner) {
        return (int) IntStream.of(partner).filter(vertex -> vertex != MaximumMatching.UNMATCHED).count();
    }

    private static void assertConsistent(int[] partner, MaximumMatching.Compatibility graph) {
        for (int vertex = 0; vertex < partner.length; vertex++) {
            if (partner[vertex] != MaximumMatching.UNMATCHED) {
                assertThat(partner[partner[vertex]]).isEqualTo(vertex);
                assertThat(graph.allows(vertex, partner[vertex])).isTrue();
            }
        }
    }

}
