package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PriorityMatchingTest {

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

    private static MaximumMatching.Compatibility edges(int... endpoints) {
        Set<RandomGraph.Edge> allowed = new HashSet<>();
        for (int index = 0; index < endpoints.length; index += 2) {
            allowed.add(RandomGraph.Edge.between(endpoints[index], endpoints[index + 1]));
        }
        return (one, other) -> allowed.contains(RandomGraph.Edge.between(one, other));
    }

}
