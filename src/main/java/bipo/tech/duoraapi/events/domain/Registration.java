package bipo.tech.duoraapi.events.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/** A inscrição de uma pessoa num evento: uma só por par, e só a própria pessoa a vê. */
public record Registration(UUID eventId, AccountId account, Instant registeredAt) {

    public Registration {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(registeredAt, "registeredAt");
    }

}
