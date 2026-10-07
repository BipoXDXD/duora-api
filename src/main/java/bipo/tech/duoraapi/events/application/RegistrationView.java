package bipo.tech.duoraapi.events.application;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.domain.Registration;

/** A inscrição para a própria pessoa. Sem o id da conta: ela sabe quem é. */
public record RegistrationView(UUID eventId, Instant registeredAt) {

    static RegistrationView of(Registration registration) {
        return new RegistrationView(registration.eventId(), registration.registeredAt());
    }

}
