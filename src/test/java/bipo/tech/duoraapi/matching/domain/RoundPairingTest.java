package bipo.tech.duoraapi.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.identity.AccountId;

class RoundPairingTest {

    private static final long SEED = 42L;

    private static final AccountId ANA = account(1);
    private static final AccountId BIA = account(2);
    private static final AccountId CAU = account(3);
    private static final AccountId DUDA = account(4);
    private static final AccountId EDU = account(5);
    private static final AccountId FE = account(6);

    @Test
    void nobodyToPairGivesAnEmptyRound() {
        RoundPairing round = RoundPairing.draw(List.of(), Set.of(), SEED);

        assertThat(round.pairs()).isEmpty();
        assertThat(round.sittingOut()).isEmpty();
    }

    @Test
    void aLonePersonSitsOut() {
        RoundPairing round = RoundPairing.draw(List.of(fresh(ANA)), Set.of(), SEED);

        assertThat(round.pairs()).isEmpty();
        assertThat(round.sittingOut()).containsExactly(ANA);
    }

    @Test
    void twoPeopleWhoMayMeetArePaired() {
        RoundPairing round = RoundPairing.draw(List.of(fresh(ANA), fresh(BIA)), Set.of(), SEED);

        assertThat(round.pairs()).containsExactly(Pair.of(ANA, BIA));
        assertThat(round.sittingOut()).isEmpty();
    }

    @Test
    void twoPeopleWhoMayNotMeetBothSitOut() {
        RoundPairing round = RoundPairing.draw(List.of(fresh(ANA), fresh(BIA)), Set.of(Pair.of(BIA, ANA)), SEED);

        assertThat(round.pairs()).isEmpty();
        assertThat(round.sittingOut()).containsExactlyInAnyOrder(ANA, BIA);
    }

    @Test
    void everyPairForbiddenLeavesEveryoneOut() {
        Set<Pair> everyPair = Set.of(
                Pair.of(ANA, BIA), Pair.of(ANA, CAU), Pair.of(ANA, DUDA),
                Pair.of(BIA, CAU), Pair.of(BIA, DUDA), Pair.of(CAU, DUDA));

        RoundPairing round = RoundPairing.draw(
                List.of(fresh(ANA), fresh(BIA), fresh(CAU), fresh(DUDA)), everyPair, SEED);

        assertThat(round.pairs()).isEmpty();
        assertThat(round.sittingOut()).containsExactlyInAnyOrder(ANA, BIA, CAU, DUDA);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L})
    void withAnOddNumberThePersonWhoSatOutMostIsPaired(long seed) {
        RoundPairing round = RoundPairing.draw(
                List.of(fresh(ANA), fresh(BIA), new Candidate(CAU, 1)), Set.of(), seed);

        assertThat(round.pairs()).hasSize(1);
        assertThat(round.sittingOut()).hasSize(1).doesNotContain(CAU);
    }

    @Test
    void pairsEveryoneEvenWhenTheFirstChoiceOfTheMostPrioritizedWouldStrandTwoPeople() {
        // Só A-B, B-C e C-D são permitidos. Juntar B e C (os dois com prioridade) deixaria A e D de fora.
        Set<Pair> forbidden = Set.of(Pair.of(ANA, CAU), Pair.of(ANA, DUDA), Pair.of(BIA, DUDA));

        RoundPairing round = RoundPairing.draw(
                List.of(new Candidate(ANA, 1), new Candidate(BIA, 3), new Candidate(CAU, 2), fresh(DUDA)),
                forbidden, SEED);

        assertThat(round.pairs()).containsExactlyInAnyOrder(Pair.of(ANA, BIA), Pair.of(CAU, DUDA));
        assertThat(round.sittingOut()).isEmpty();
    }

    @Test
    void pairsEveryoneWhenTheOnlyWayGoesAroundAnOddCycle() {
        // O único pareamento completo é A-E, B-C, D-F. Com A-B e C-D já formados pela prioridade, o caminho que
        // chega nele passa pelo triângulo A-B-C, e só se acha contraindo esse ciclo ímpar (blossom).
        Set<Pair> allowed = Set.of(
                Pair.of(ANA, BIA), Pair.of(ANA, CAU), Pair.of(ANA, EDU), Pair.of(BIA, CAU), Pair.of(BIA, DUDA),
                Pair.of(CAU, DUDA), Pair.of(DUDA, EDU), Pair.of(DUDA, FE));
        List<AccountId> people = List.of(ANA, BIA, CAU, DUDA, EDU, FE);

        RoundPairing round = RoundPairing.draw(
                List.of(new Candidate(ANA, 5), new Candidate(BIA, 4), new Candidate(CAU, 3),
                        new Candidate(DUDA, 2), new Candidate(EDU, 1), fresh(FE)),
                complementOf(people, allowed), SEED);

        assertThat(round.pairs()).containsExactlyInAnyOrder(Pair.of(ANA, EDU), Pair.of(BIA, CAU), Pair.of(DUDA, FE));
        assertThat(round.sittingOut()).isEmpty();
    }

    @Test
    void whenOnlyOnePersonCanMeetTheOthersTheOneWhoSatOutMostMeetsThem() {
        // Só a Ana pode encontrar Bia, Cau e Duda; entre eles, todos os pares são proibidos.
        Set<Pair> forbidden = Set.of(Pair.of(BIA, CAU), Pair.of(BIA, DUDA), Pair.of(CAU, DUDA));

        RoundPairing round = RoundPairing.draw(
                List.of(fresh(ANA), new Candidate(BIA, 1), new Candidate(CAU, 2), fresh(DUDA)), forbidden, SEED);

        assertThat(round.pairs()).containsExactly(Pair.of(ANA, CAU));
        assertThat(round.sittingOut()).containsExactlyInAnyOrder(BIA, DUDA);
    }

    @Test
    void aForbiddenPairWithSomeoneWhoIsNotACandidateChangesNothing() {
        RoundPairing round = RoundPairing.draw(
                List.of(fresh(ANA), fresh(BIA)), Set.of(Pair.of(ANA, CAU)), SEED);

        assertThat(round.pairs()).containsExactly(Pair.of(ANA, BIA));
    }

    @Test
    void theSameCandidatesAndSeedGiveTheSameRoundInWhateverOrderTheCandidatesCome() {
        List<Candidate> candidates = List.of(fresh(ANA), fresh(BIA), fresh(CAU), fresh(DUDA), fresh(EDU));
        List<Candidate> reversed = candidates.reversed();

        assertThat(RoundPairing.draw(reversed, Set.of(), SEED)).isEqualTo(RoundPairing.draw(candidates, Set.of(), SEED));
    }

    @Test
    void differentSeedsGiveDifferentRounds() {
        List<Candidate> candidates = List.of(fresh(ANA), fresh(BIA), fresh(CAU), fresh(DUDA), fresh(EDU), fresh(FE));

        Set<RoundPairing> rounds = new HashSet<>();
        LongStream.range(0, 20).forEach(seed -> rounds.add(RoundPairing.draw(candidates, Set.of(), seed)));

        assertThat(rounds).hasSizeGreaterThan(1);
    }

    @Test
    void theSameAccountTwiceAmongTheCandidatesIsAProgrammingError() {
        assertThatThrownBy(() -> RoundPairing.draw(List.of(fresh(ANA), new Candidate(ANA, 1)), Set.of(), SEED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an account appears more than once among the candidates");
    }

    @Test
    void theResultCannotBeChangedByWhoReadsIt() {
        RoundPairing round = RoundPairing.draw(List.of(fresh(ANA), fresh(BIA), fresh(CAU)), Set.of(), SEED);

        assertThatThrownBy(() -> round.pairs().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> round.sittingOut().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static Candidate fresh(AccountId account) {
        return new Candidate(account, 0);
    }

    private static AccountId account(int number) {
        return new AccountId(new UUID(0L, number));
    }

    private static Set<Pair> complementOf(List<AccountId> people, Set<Pair> allowed) {
        Set<Pair> forbidden = new HashSet<>();
        for (int i = 0; i < people.size(); i++) {
            for (int j = i + 1; j < people.size(); j++) {
                Pair pair = Pair.of(people.get(i), people.get(j));
                if (!allowed.contains(pair)) {
                    forbidden.add(pair);
                }
            }
        }
        return forbidden;
    }

}
