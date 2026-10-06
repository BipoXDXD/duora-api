package bipo.tech.duoraapi.events.application;

/** O resultado de pedir a inscrição: a inscrição e se ela foi criada agora ou já existia. */
public record RegistrationOutcome(RegistrationView registration, boolean created) {
}
