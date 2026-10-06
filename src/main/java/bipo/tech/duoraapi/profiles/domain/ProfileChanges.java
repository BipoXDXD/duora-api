package bipo.tech.duoraapi.profiles.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Uma edição parcial do perfil, já com cada valor válido no seu tipo. */
public record ProfileChanges(
        FieldChange<DisplayName> displayName,
        FieldChange<LocalDate> birthDate,
        FieldChange<Bio> bio,
        FieldChange<Region> region) {

    public ProfileChanges {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(birthDate, "birthDate");
        Objects.requireNonNull(bio, "bio");
        Objects.requireNonNull(region, "region");
    }

}
