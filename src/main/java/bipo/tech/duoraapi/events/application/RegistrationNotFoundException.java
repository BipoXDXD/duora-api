package bipo.tech.duoraapi.events.application;

/** Quem chama não está inscrito nesse evento. Só existe a própria inscrição: não há como pedir a de outra pessoa. */
public class RegistrationNotFoundException extends RuntimeException {

    public RegistrationNotFoundException() {
        super("registration not found");
    }

}
