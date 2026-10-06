package bipo.tech.duoraapi.profiles.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Como a pessoa se apresenta no Duora. Cada conta tem exatamente um perfil, que começa vazio e é
 * preenchido aos poucos; ele está completo quando tem nome, data de nascimento de maior de idade e
 * região.
 *
 * <p>Não é {@code final} e tem construtor sem argumentos porque o JPA exige. O perfil vazio nasce no
 * banco por {@link ProfileRepository#insertEmptyIfAbsent}, sem passar pelo JPA.
 */
@Entity
@Table(name = "profile")
public class Profile {

    @Id
    private UUID accountId;

    private String displayName;

    private LocalDate birthDate;

    private String bio;

    /** Código ISO 3166-2, como em {@link Region#code()}. */
    private String region;

    /** Conta as edições gravadas; é o ETag do perfil. O perfil vazio está na versão 0. */
    @Version
    private long version;

    protected Profile() {
        // exigido pelo JPA
    }

    private Profile(UUID accountId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
    }

    /** O perfil de quem ainda não preencheu nada, igual ao que o banco guarda antes da primeira edição. */
    public static Profile emptyFor(UUID accountId) {
        return new Profile(accountId);
    }

    /**
     * Aplica a edição inteira ou nada. Nome, data de nascimento e região não podem ser apagados; a data
     * de nascimento só pode ser informada uma vez, e só se for de um maior de idade.
     */
    public void apply(ProfileChanges changes, Instant now) {
        DisplayName newDisplayName = required("displayName", changes.displayName(), displayName().orElse(null));
        LocalDate newBirthDate = changedBirthDate(changes.birthDate(), now);
        Bio newBio = optional(changes.bio(), bio().orElse(null));
        Region newRegion = required("region", changes.region(), region().orElse(null));

        displayName = newDisplayName == null ? null : newDisplayName.value();
        birthDate = newBirthDate;
        bio = newBio == null ? null : newBio.value();
        region = newRegion == null ? null : newRegion.code();
    }

    public boolean isComplete(Instant now) {
        return displayName != null && region != null && birthDate != null && AgePolicy.isAdult(birthDate, now);
    }

    public UUID accountId() {
        return accountId;
    }

    public Optional<DisplayName> displayName() {
        return Optional.ofNullable(displayName).map(DisplayName::new);
    }

    public Optional<LocalDate> birthDate() {
        return Optional.ofNullable(birthDate);
    }

    public Optional<Bio> bio() {
        return Optional.ofNullable(bio).map(Bio::new);
    }

    public Optional<Region> region() {
        return Optional.ofNullable(region).map(Region::fromCode);
    }

    public long version() {
        return version;
    }

    private LocalDate changedBirthDate(FieldChange<LocalDate> change, Instant now) {
        LocalDate requested = required("birthDate", change, birthDate);
        if (Objects.equals(requested, birthDate)) {
            return birthDate;
        }
        if (birthDate != null) {
            throw new BirthDateAlreadySetException();
        }
        if (!AgePolicy.isAdult(requested, now)) {
            throw new InvalidProfileException("birthDate must be at least " + AgePolicy.ADULT_AGE + " years ago");
        }
        if (!AgePolicy.isPlausible(requested, now)) {
            throw new InvalidProfileException("birthDate must be at most " + AgePolicy.MAX_PLAUSIBLE_AGE + " years ago");
        }
        return requested;
    }

    private static <T> T required(String field, FieldChange<T> change, T current) {
        return switch (change) {
            case FieldChange.Keep<T> _ -> current;
            case FieldChange.Clear<T> _ -> throw new InvalidProfileException(field + " cannot be removed");
            case FieldChange.SetTo<T>(T value) -> value;
        };
    }

    private static <T> T optional(FieldChange<T> change, T current) {
        return switch (change) {
            case FieldChange.Keep<T> _ -> current;
            case FieldChange.Clear<T> _ -> null;
            case FieldChange.SetTo<T>(T value) -> value;
        };
    }

}
