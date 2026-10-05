package bipo.tech.duoraapi.waitlist.domain;

public class InvalidEmailAddressException extends IllegalArgumentException {

    public InvalidEmailAddressException(String message) {
        super(message);
    }

}
