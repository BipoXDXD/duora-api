package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Propriedades do sorteio conferidas em grupos aleatórios, com semente fixa por caso para o resultado ser
 * reproduzível (o jqwik não está no projeto; ver docs/adr/0017). O máximo de pares e a justiça são conferidos
 * contra uma busca exaustiva, que só é viável em grupos pequenos.
 */
class RoundPairingRandomCasesTest {

    private static final int CASES = 300;
    private static final int MAX_PEOPLE = 9;
    private static final int MAX_ROUNDS_SAT_OUT = 2;

    static LongStream caseSeeds() {
        return LongStream.range(0, CASES);
    }

    @ParameterizedTest
    @MethodSource("caseSeeds")
    void everyCandidateEndsInExactlyOnePairOrSittingOut(long caseSeed) {
        Group group = Group.random(caseSeed);

        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), caseSeed);

        List<AccountId> placed = new ArrayList<>(round.sittingOut());
        round.pairs().forEach(pair -> placed.addAll(List.of(pair.first(), pair.second())));
        assertThat(placed).containsExactlyInAnyOrderElementsOf(group.accounts());
    }

    @ParameterizedTest
    @MethodSource("caseSeeds")
    void noForbiddenPairIsFormed(long caseSeed) {
        Group group = Group.random(caseSeed);

        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), caseSeed);

        assertThat(round.pairs()).filteredOn(group.forbidden()::contains).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("caseSeeds")
    void formsAsManyPairsAsAnyPairingCould(long caseSeed) {
        Group group = Group.random(caseSeed);

        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), caseSeed);

        assertThat(round.pairs()).hasSize(group.largestPossibleNumberOfPairs());
    }

    @ParameterizedTest
    @MethodSource("caseSeeds")
    void nobodySitsOutInPlaceOfSomeoneWhoSatOutLess(long caseSeed) {
        Group group = Group.random(caseSeed);

        RoundPairing round = RoundPairing.draw(group.candidates(), group.forbidden(), caseSeed);

        assertThat(group.unfairSwaps(round)).isEmpty();
    }

    /** Um grupo de candidatos com pares proibidos, e a busca exaustiva sobre ele. */
    private record Group(List<Candidate> candidates, Set<Pair> forbidden) {

        static Group random(long caseSeed) {
            Random random = new Random(caseSeed);
            int people = random.nextInt(MAX_PEOPLE + 1);
            double forbiddenShare = random.nextDouble();
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < people; i++) {
                candidates.add(new Candidate(new AccountId(new UUID(0L, i + 1L)), random.nextInt(MAX_ROUNDS_SAT_OUT + 1)));
            }
            Set<Pair> forbidden = new HashSet<>();
            for (int i = 0; i < people; i++) {
                for (int j = i + 1; j < people; j++) {
                    if (random.nextDouble() < forbiddenShare) {
                        forbidden.add(Pair.of(candidates.get(i).account(), candidates.get(j).account()));
                    }
                }
            }
            return new Group(candidates, forbidden);
        }

        List<AccountId> accounts() {
            return candidates.stream().map(Candidate::account).toList();
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
            for (int i = from; i < candidates.size(); i++) {
                if ((covered & 1 << i) != 0) {
                    continue;
                }
                for (int j = i + 1; j < candidates.size(); j++) {
                    boolean free = (covered & 1 << j) == 0;
                    if (free && !forbidden.contains(Pair.of(candidates.get(i).account(), candidates.get(j).account()))) {
                        coverableSets(i + 1, covered | 1 << i | 1 << j, sets);
                    }
                }
            }
        }

        private int bit(AccountId account) {
            return 1 << accounts().indexOf(account);
        }

        private int roundsSatOut(AccountId account) {
            return candidates.get(accounts().indexOf(account)).roundsSatOut();
        }

    }

}
