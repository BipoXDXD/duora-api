package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Propriedades do sorteio em grupos gerados pelo jqwik. O máximo de pares e a justiça são conferidos contra uma
 * busca exaustiva, que só é viável em grupos pequenos. Numa falha, o relatório traz o grupo mínimo (os pares
 * proibidos como arestas entre os índices das pessoas) e a semente para reproduzir.
 */
class RoundPairingPropertiesTest {

    private static final int MAX_PEOPLE = 9;
    private static final int MAX_ROUNDS_SAT_OUT = 2;

    @Property
    void everyCandidateEndsInExactlyOnePairOrSittingOut(@ForAll("groups") Group group, @ForAll long seed) {
        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), seed);

        List<AccountId> placed = new ArrayList<>(round.sittingOut());
        round.pairs().forEach(pair -> placed.addAll(List.of(pair.first(), pair.second())));
        assertThat(placed).containsExactlyInAnyOrderElementsOf(group.accounts());
    }

    @Property
    void noForbiddenPairIsFormed(@ForAll("groups") Group group, @ForAll long seed) {
        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), seed);

        assertThat(round.pairs()).filteredOn(group.forbidden()::contains).isEmpty();
    }

    @Property
    void formsAsManyPairsAsAnyPairingCould(@ForAll("groups") Group group, @ForAll long seed) {
        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), seed);

        assertThat(round.pairs()).hasSize(group.largestPossibleNumberOfPairs());
    }

    @Property
    void nobodySitsOutInPlaceOfSomeoneWhoSatOutLess(@ForAll("groups") Group group, @ForAll long seed) {
        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), seed);

        assertThat(group.unfairSwaps(round)).isEmpty();
    }

    @Property
    void theSameGroupAndSeedGiveTheSameRoundInWhateverOrderTheCandidatesCome(@ForAll("groups") Group group,
            @ForAll long seed, @ForAll Random arrivalOrder) {
        List<Candidate> shuffled = new ArrayList<>(group.candidates());
        Collections.shuffle(shuffled, arrivalOrder);

        assertThat(RoundPairing.draw(shuffled, group.forbidden(), seed))
                .isEqualTo(RoundPairing.draw(group.candidates(), group.forbidden(), seed));
    }

    @Provide
    Arbitrary<Group> groups() {
        Arbitrary<RandomGraph> forbiddenPairs =
                RandomGraph.withVertices(0, MAX_PEOPLE, RandomGraph.MIN_EDGE_PERCENT, RandomGraph.MAX_EDGE_PERCENT);
        return forbiddenPairs.flatMap(forbidden ->
                Arbitraries.integers().between(0, MAX_ROUNDS_SAT_OUT).list().ofSize(forbidden.vertices())
                        .map(roundsSatOut -> new Group(forbidden, roundsSatOut)));
    }

    /**
     * Um grupo de candidatos e a busca exaustiva sobre ele. A pessoa {@code i} tem o id {@code i + 1} e ficou de
     * fora {@code roundsSatOut.get(i)} vezes; cada aresta do grafo é um par proibido.
     */
    record Group(RandomGraph forbiddenGraph, List<Integer> roundsSatOut) {

        Group {
            roundsSatOut = List.copyOf(roundsSatOut);
        }

        List<AccountId> accounts() {
            return IntStream.range(0, forbiddenGraph.vertices()).mapToObj(Group::account).toList();
        }

        List<Candidate> candidates() {
            return IntStream.range(0, forbiddenGraph.vertices())
                    .mapToObj(person -> new Candidate(account(person), roundsSatOut.get(person)))
                    .toList();
        }

        Set<Pair> forbidden() {
            return forbiddenGraph.edges().stream()
                    .map(edge -> Pair.of(account(edge.one()), account(edge.other())))
                    .collect(Collectors.toSet());
        }

        int largestPossibleNumberOfPairs() {
            return Integer.bitCount(largestCoverableSets().iterator().next()) / 2;
        }

        /**
         * Trocas que deixariam o sorteio mais justo: tirar de um par alguém que ficou de fora menos vezes e pôr
         * no lugar alguém que ficou de fora mais vezes, mantendo o máximo de pares.
         */
        List<String> unfairSwaps(RoundPairing round) {
            int covered = 0;
            for (Pair pair : round.pairs()) {
                covered |= bit(pair.first()) | bit(pair.second());
            }
            Set<Integer> best = largestCoverableSets();
            List<String> swaps = new ArrayList<>();
            for (AccountId out : round.sittingOut()) {
                for (Pair pair : round.pairs()) {
                    for (AccountId in : List.of(pair.first(), pair.second())) {
                        boolean satOutMore = roundsSatOut(out) > roundsSatOut(in);
                        if (satOutMore && best.contains(covered & ~bit(in) | bit(out))) {
                            swaps.add(out + " could take the place of " + in);
                        }
                    }
                }
            }
            return swaps;
        }

        private static AccountId account(int person) {
            return new AccountId(new UUID(0L, person + 1L));
        }

        /** Os conjuntos de pessoas, em bits, que algum pareamento máximo cobre. */
        private Set<Integer> largestCoverableSets() {
            Set<Integer> all = new HashSet<>();
            coverableSets(0, 0, all);
            int largest = all.stream().mapToInt(Integer::bitCount).max().orElseThrow();
            Set<Integer> best = new HashSet<>();
            all.stream().filter(set -> Integer.bitCount(set) == largest).forEach(best::add);
            return best;
        }

        private void coverableSets(int from, int covered, Set<Integer> sets) {
            sets.add(covered);
            for (int i = from; i < forbiddenGraph.vertices(); i++) {
                if ((covered & 1 << i) != 0) {
                    continue;
                }
                for (int j = i + 1; j < forbiddenGraph.vertices(); j++) {
                    boolean free = (covered & 1 << j) == 0;
                    if (free && !isForbidden(i, j)) {
                        coverableSets(i + 1, covered | 1 << i | 1 << j, sets);
                    }
                }
            }
        }

        private boolean isForbidden(int one, int other) {
            return forbiddenGraph.allows(one, other);
        }

        private int bit(AccountId account) {
            return 1 << accounts().indexOf(account);
        }

        private int roundsSatOut(AccountId account) {
            return roundsSatOut.get(accounts().indexOf(account));
        }

    }

}
