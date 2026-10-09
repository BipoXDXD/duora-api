package bipo.tech.duoraapi.matching.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Tuple;

/**
 * Grafo simples gerado pelo jqwik para as propriedades do emparelhamento. A densidade de arestas muda de um grafo
 * para outro, e o shrinking tira arestas e vértices até sobrar o menor grafo que ainda falha.
 */
record RandomGraph(int vertices, Set<Edge> edges) implements MaximumMatching.Compatibility {

    /** Da densidade mais rala à mais cheia: com 0 ou 100% o grafo não teria escolha a fazer. */
    static final int MIN_EDGE_PERCENT = 1;
    static final int MAX_EDGE_PERCENT = 99;

    private static final int ALL_PERCENT = 100;

    RandomGraph {
        edges = Set.copyOf(edges);
    }

    /**
     * @param minEdgePercent a menor chance, em %, de cada aresta possível existir, de {@link #MIN_EDGE_PERCENT}
     *                       a {@link #MAX_EDGE_PERCENT}
     * @param maxEdgePercent a maior chance, em %, de cada aresta possível existir, na mesma faixa
     */
    static Arbitrary<RandomGraph> withVertices(int minVertices, int maxVertices, int minEdgePercent, int maxEdgePercent) {
        return Arbitraries.integers().between(minVertices, maxVertices).flatMap(vertices ->
                Arbitraries.integers().between(minEdgePercent, maxEdgePercent).flatMap(edgePercent ->
                        edgePresence(edgePercent).list().ofSize(possibleEdges(vertices))
                                .map(present -> new RandomGraph(vertices, edgesOf(vertices, present)))));
    }

    @Override
    public boolean allows(int one, int other) {
        return edges.contains(Edge.between(one, other));
    }

    /** O falso vem primeiro: o shrinking prefere o grafo com menos arestas. */
    private static Arbitrary<Boolean> edgePresence(int edgePercent) {
        return Arbitraries.frequency(Tuple.of(ALL_PERCENT - edgePercent, false), Tuple.of(edgePercent, true));
    }

    private static int possibleEdges(int vertices) {
        return vertices * (vertices - 1) / 2;
    }

    private static Set<Edge> edgesOf(int vertices, List<Boolean> present) {
        Set<Edge> edges = new HashSet<>();
        int index = 0;
        for (int one = 0; one < vertices; one++) {
            for (int other = one + 1; other < vertices; other++) {
                if (present.get(index++)) {
                    edges.add(new Edge(one, other));
                }
            }
        }
        return edges;
    }

    record Edge(int one, int other) {

        static Edge between(int one, int other) {
            return new Edge(Math.min(one, other), Math.max(one, other));
        }

        @Override
        public String toString() {
            return one + "-" + other;
        }

    }

}
