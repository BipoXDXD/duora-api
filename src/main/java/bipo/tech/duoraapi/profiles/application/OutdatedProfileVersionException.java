package bipo.tech.duoraapi.profiles.application;

/** A edição partiu de uma leitura antiga: outra edição foi gravada depois dela. */
public class OutdatedProfileVersionException extends RuntimeException {

    public OutdatedProfileVersionException() {
        super("the profile changed since it was read; read it again and reapply the changes");
    }

}
