package bipo.tech.duoraapi.profiles.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonProperty;

import bipo.tech.duoraapi.profiles.domain.Bio;
import bipo.tech.duoraapi.profiles.domain.DisplayName;
import bipo.tech.duoraapi.profiles.domain.FieldChange;
import bipo.tech.duoraapi.profiles.domain.InvalidProfileException;
import bipo.tech.duoraapi.profiles.domain.ProfileChanges;
import bipo.tech.duoraapi.profiles.domain.Region;

/**
 * Corpo do PATCH, como um JSON Merge Patch de um nível: campo ausente não muda, null apaga, valor troca.
 * Classe com setters, e não record, porque o Jackson só chama o setter das chaves presentes no JSON,
 * inclusive com null: é o que distingue ausente de null. Chave desconhecida é recusada com 400
 * (spring.jackson.deserialization.fail-on-unknown-properties). Os valores chegam como texto e viram
 * tipos do domínio em {@link #toChanges()}.
 */
final class EditProfileRequest {

    private FieldChange<String> displayName = FieldChange.keep();
    private FieldChange<String> birthDate = FieldChange.keep();
    private FieldChange<String> bio = FieldChange.keep();
    private FieldChange<String> region = FieldChange.keep();

    @JsonProperty("displayName")
    void displayName(String value) {
        displayName = presentValue(value);
    }

    /** Texto, e não LocalDate: o Jackson aceitaria um número como dia da época (7000 = 1989-03-01). */
    @JsonProperty("birthDate")
    void birthDate(String value) {
        birthDate = presentValue(value);
    }

    @JsonProperty("bio")
    void bio(String value) {
        bio = presentValue(value);
    }

    @JsonProperty("region")
    void region(String value) {
        region = presentValue(value);
    }

    ProfileChanges toChanges() {
        return new ProfileChanges(
                displayName.map(DisplayName::new),
                birthDate.map(EditProfileRequest::parseBirthDate),
                bioChange(),
                region.map(Region::fromCode));
    }

    /** Apagar o texto no formulário envia "": é o mesmo que null. */
    private FieldChange<Bio> bioChange() {
        return switch (bio) {
            case FieldChange.Keep<String> _ -> FieldChange.keep();
            case FieldChange.Clear<String> _ -> FieldChange.clear();
            case FieldChange.SetTo<String>(String text) ->
                    Bio.fromText(text).<FieldChange<Bio>>map(FieldChange::setTo).orElseGet(FieldChange::clear);
        };
    }

    /** ISO 8601 estrito: o resolvedor do ISO_LOCAL_DATE recusa dia inexistente, como 30 de fevereiro. */
    private static LocalDate parseBirthDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidProfileException("birthDate must be a date in the format 1990-05-10");
        }
    }

    private static FieldChange<String> presentValue(String value) {
        return value == null ? FieldChange.clear() : FieldChange.setTo(value);
    }

}
