package bipo.tech.duoraapi.events.api;

import java.time.Instant;
import java.util.UUID;

import bipo.tech.duoraapi.events.application.RegistrationView;

/** A própria inscrição: o evento e quando ela foi feita. */
record RegistrationResponse(UUID eventId, Instant registeredAt) {

    static RegistrationResponse of(RegistrationView registration) {
        return new RegistrationResponse(registration.eventId(), registration.registeredAt());
    }

}
