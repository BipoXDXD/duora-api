package bipo.tech.duoraapi.profiles.application;

import java.time.Instant;
import java.time.LocalDate;

import bipo.tech.duoraapi.profiles.domain.Bio;
import bipo.tech.duoraapi.profiles.domain.DisplayName;
import bipo.tech.duoraapi.profiles.domain.Profile;
import bipo.tech.duoraapi.profiles.domain.Region;

/**
 * O perfil como o próprio dono o vê, num instante: campos não preenchidos são null, e {@code complete}
 * já considera a idade naquele instante. {@code version} é o ETag.
 */
public record ProfileView(String displayName, LocalDate birthDate, String bio, String regionCode,
        boolean complete, long version) {

    static ProfileView of(Profile profile, Instant now) {
        return new ProfileView(
                profile.displayName().map(DisplayName::value).orElse(null),
                profile.birthDate().orElse(null),
                profile.bio().map(Bio::value).orElse(null),
                profile.region().map(Region::code).orElse(null),
                profile.isComplete(now),
                profile.version());
    }

    /** Nome, data de nascimento e bio são dados pessoais: fora dos logs. */
    @Override
    public String toString() {
        return "ProfileView[complete=" + complete + ", version=" + version + "]";
    }

}
