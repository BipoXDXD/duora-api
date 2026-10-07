package bipo.tech.duoraapi.profiles.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProfileTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");
    private static final Instant NOW = Instant.parse("2026-10-05T17:00:00Z");
    private static final LocalDate ADULT_BIRTH_DATE = LocalDate.parse("1990-05-10");

    @Test
    void emptyProfileHasNothingFilledIn() {
        var profile = Profile.emptyFor(ACCOUNT_ID);

        assertThat(profile.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(profile.displayName()).isEmpty();
        assertThat(profile.birthDate()).isEmpty();
        assertThat(profile.bio()).isEmpty();
        assertThat(profile.region()).isEmpty();
        assertThat(profile.version()).isZero();
    }

    @Test
    void emptyProfileNeedsAnAccountId() {
        assertThatThrownBy(() -> Profile.emptyFor(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("accountId");
    }

    @Test
    void emptyProfileIsIncomplete() {
        assertThat(Profile.emptyFor(ACCOUNT_ID).isComplete(NOW)).isFalse();
    }

    @Test
    void appliesEveryChangedField() {
        var profile = Profile.emptyFor(ACCOUNT_ID);

        profile.apply(fullChanges(), NOW);

        assertThat(profile.displayName()).contains(new DisplayName("Ana"));
        assertThat(profile.birthDate()).contains(ADULT_BIRTH_DATE);
        assertThat(profile.bio()).isEqualTo(Bio.fromText("Gosto de trilhas"));
        assertThat(profile.region()).contains(Region.SP);
    }

    @Test
    void profileWithNameBirthDateAndRegionIsComplete() {
        var profile = Profile.emptyFor(ACCOUNT_ID);

        profile.apply(new ProfileChanges(FieldChange.setTo(new DisplayName("Ana")), FieldChange.setTo(ADULT_BIRTH_DATE),
                FieldChange.keep(), FieldChange.setTo(Region.SP)), NOW);

        assertThat(profile.isComplete(NOW)).isTrue();
    }

    @Test
    void profileWithoutNameIsIncomplete() {
        var profile = Profile.emptyFor(ACCOUNT_ID);

        profile.apply(new ProfileChanges(FieldChange.keep(), FieldChange.setTo(ADULT_BIRTH_DATE),
                FieldChange.keep(), FieldChange.setTo(Region.SP)), NOW);

        assertThat(profile.isComplete(NOW)).isFalse();
    }

    @Test
    void profileWithoutBirthDateIsIncomplete() {
        var profile = Profile.emptyFor(ACCOUNT_ID);

        profile.apply(new ProfileChanges(FieldChange.setTo(new DisplayName("Ana")), FieldChange.keep(),
                FieldChange.keep(), FieldChange.setTo(Region.SP)), NOW);

        assertThat(profile.isComplete(NOW)).isFalse();
    }

    /** A idade é calculada no instante pedido, e não guardada: um dia antes dos 18, o perfil não está completo. */
    @Test
    void completenessFollowsTheAgeAtTheGivenInstant() {
        var profile = Profile.emptyFor(ACCOUNT_ID);
        profile.apply(new ProfileChanges(FieldChange.setTo(new DisplayName("Ana")),
                FieldChange.setTo(LocalDate.parse("2008-10-05")), FieldChange.keep(), FieldChange.setTo(Region.SP)), NOW);

        assertThat(profile.isComplete(NOW)).isTrue();
        assertThat(profile.isComplete(NOW.minus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void profileWithoutRegionIsIncomplete() {
        var profile = Profile.emptyFor(ACCOUNT_ID);

        profile.apply(new ProfileChanges(FieldChange.setTo(new DisplayName("Ana")), FieldChange.setTo(ADULT_BIRTH_DATE),
                FieldChange.keep(), FieldChange.keep()), NOW);

        assertThat(profile.isComplete(NOW)).isFalse();
    }

    @Test
    void keptFieldsStayAsTheyWere() {
        var profile = Profile.emptyFor(ACCOUNT_ID);
        profile.apply(fullChanges(), NOW);

        profile.apply(new ProfileChanges(FieldChange.keep(), FieldChange.keep(), FieldChange.keep(), FieldChange.keep()), NOW);

        assertThat(profile.displayName()).contains(new DisplayName("Ana"));
        assertThat(profile.birthDate()).contains(ADULT_BIRTH_DATE);
        assertThat(profile.bio()).isEqualTo(Bio.fromText("Gosto de trilhas"));
        assertThat(profile.region()).contains(Region.SP);
    }

    @Test
    void clearingTheBioRemovesIt() {
        var profile = Profile.emptyFor(ACCOUNT_ID);
        profile.apply(fullChanges(), NOW);

        profile.apply(new ProfileChanges(FieldChange.keep(), FieldChange.keep(), FieldChange.clear(), FieldChange.keep()), NOW);

        assertThat(profile.bio()).isEmpty();
        assertThat(profile.isComplete(NOW)).isTrue();
    }

    @Test
    void displayNameCannotBeRemoved() {
        var changes = new ProfileChanges(FieldChange.clear(), FieldChange.keep(), FieldChange.keep(), FieldChange.keep());

        assertThatThrownBy(() -> Profile.emptyFor(ACCOUNT_ID).apply(changes, NOW))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("displayName cannot be removed");
    }

    @Test
    void birthDateCannotBeRemoved() {
        var changes = new ProfileChanges(FieldChange.keep(), FieldChange.clear(), FieldChange.keep(), FieldChange.keep());

        assertThatThrownBy(() -> Profile.emptyFor(ACCOUNT_ID).apply(changes, NOW))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("birthDate cannot be removed");
    }

    @Test
    void regionCannotBeRemoved() {
        var changes = new ProfileChanges(FieldChange.keep(), FieldChange.keep(), FieldChange.keep(), FieldChange.clear());

        assertThatThrownBy(() -> Profile.emptyFor(ACCOUNT_ID).apply(changes, NOW))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("region cannot be removed");
    }

    /** O Duora é 18+: a data de quem ainda não tem 18 anos nem chega a ser guardada. */
    @Test
    void birthDateOfAMinorIsRejected() {
        var profile = Profile.emptyFor(ACCOUNT_ID);
        var changes = birthDateChange(LocalDate.parse("2008-10-06"));

        assertThatThrownBy(() -> profile.apply(changes, NOW))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("birthDate must be at least 18 years ago");
        assertThat(profile.birthDate()).isEmpty();
    }

    @Test
    void birthDateOfSomeoneTurningEighteenTodayIsAccepted() {
        var profile = Profile.emptyFor(ACCOUNT_ID);

        profile.apply(birthDateChange(LocalDate.parse("2008-10-05")), NOW);

        assertThat(profile.birthDate()).contains(LocalDate.parse("2008-10-05"));
    }

    @Test
    void implausibleBirthDateIsRejected() {
        var changes = birthDateChange(LocalDate.parse("1905-10-05"));

        assertThatThrownBy(() -> Profile.emptyFor(ACCOUNT_ID).apply(changes, NOW))
                .isInstanceOf(InvalidProfileException.class)
                .hasMessage("birthDate must be at most 120 years ago");
    }

    /** Trocar a data depois liberaria quem mentiu a idade, ou quem tenta escapar de uma regra por idade. */
    @Test
    void birthDateCannotChangeOnceSet() {
        var profile = Profile.emptyFor(ACCOUNT_ID);
        profile.apply(birthDateChange(ADULT_BIRTH_DATE), NOW);
        var changes = birthDateChange(LocalDate.parse("1991-05-10"));

        assertThatThrownBy(() -> profile.apply(changes, NOW)).isInstanceOf(BirthDateAlreadySetException.class);
        assertThat(profile.birthDate()).contains(ADULT_BIRTH_DATE);
    }

    /** Reenviar o formulário inteiro, com a mesma data, não é troca. */
    @Test
    void sendingTheSameBirthDateAgainIsAccepted() {
        var profile = Profile.emptyFor(ACCOUNT_ID);
        profile.apply(birthDateChange(ADULT_BIRTH_DATE), NOW);

        profile.apply(birthDateChange(ADULT_BIRTH_DATE), NOW);

        assertThat(profile.birthDate()).contains(ADULT_BIRTH_DATE);
    }

    /** Uma mudança inválida não deixa as válidas pela metade. */
    @Test
    void rejectedChangesLeaveTheProfileUntouched() {
        var profile = Profile.emptyFor(ACCOUNT_ID);
        var changes = new ProfileChanges(FieldChange.setTo(new DisplayName("Ana")), FieldChange.keep(),
                FieldChange.keep(), FieldChange.clear());

        assertThatThrownBy(() -> profile.apply(changes, NOW)).isInstanceOf(InvalidProfileException.class);
        assertThat(profile.displayName()).isEmpty();
    }

    private static ProfileChanges fullChanges() {
        return new ProfileChanges(FieldChange.setTo(new DisplayName("Ana")), FieldChange.setTo(ADULT_BIRTH_DATE),
                FieldChange.setTo(Bio.fromText("Gosto de trilhas").orElseThrow()), FieldChange.setTo(Region.SP));
    }

    private static ProfileChanges birthDateChange(LocalDate birthDate) {
        return new ProfileChanges(FieldChange.keep(), FieldChange.setTo(birthDate), FieldChange.keep(), FieldChange.keep());
    }

}
