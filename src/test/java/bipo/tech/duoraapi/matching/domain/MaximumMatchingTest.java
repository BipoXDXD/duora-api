package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class MaximumMatchingTest {

    @Test
    void emptyGraphMatchesNobody() {
        assertThat(MaximumMatching.partners(0, (one, other) -> false)).isEmpty();
        assertThat(MaximumMatching.partners(4, (one, other) -> false)).containsOnly(MaximumMatching.UNMATCHED);
    }

    @Test
    void triangleMatchesOnePairAndLeavesOneOut() {
        var graph = edges(0, 1, 1, 2, 0, 2);

        int[] partner = MaximumMatching.partners(3, graph);

        assertThat(matchedCount(partner)).isEqualTo(2);
        assertConsistent(partner, graph);
    }

    /** O ciclo ímpar precisa ser contraído: sem isso o terceiro par não aparece. */
    @Test
    void fiveCycleWithAPendantHasAPerfectMatching() {
        var graph = edges(0, 1, 1, 2, 2, 3, 3, 4, 4, 0, 0, 5);

        int[] partner = MaximumMatching.partners(6, graph);

        assertThat(partner).containsExactly(5, 2, 1, 4, 3, 0);
    }

    @Test
    void twoTrianglesJoinedByABridgeHaveAPerfectMatching() {
        var graph = edges(0, 1, 1, 2, 0, 2, 3, 4, 4, 5, 3, 5, 2, 3);

        int[] partner = MaximumMatching.partners(6, graph);

        assertThat(matchedCount(partner)).isEqualTo(6);
        assertConsistent(partner, graph);
    }

    @Test
    void petersenGraphHasAPerfectMatching() {
        var graph = edges(0, 1, 1, 2, 2, 3, 3, 4, 4, 0,
                0, 5, 1, 6, 2, 7, 3, 8, 4, 9,
                5, 7, 7, 9, 9, 6, 6, 8, 8, 5);

        int[] partner = MaximumMatching.partners(10, graph);

        assertThat(matchedCount(partner)).isEqualTo(10);
        assertConsistent(partner, graph);
    }

    @Test
    void completeGraphOnOddVerticesLeavesExactlyOneOut() {
        int[] partner = MaximumMatching.partners(7, (one, other) -> true);

        assertThat(matchedCount(partner)).isEqualTo(6);
    }

    private static MaximumMatching.Compatibility edges(int... endpoints) {
        Set<Long> allowed = new HashSet<>();
        for (int index = 0; index < endpoints.length; index += 2) {
            allowed.add(key(endpoints[index], endpoints[index + 1]));
        }
        return (one, other) -> allowed.contains(key(one, other));
    }

    private static long key(int one, int other) {
        return Math.min(one, other) * 100L + Math.max(one, other);
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
