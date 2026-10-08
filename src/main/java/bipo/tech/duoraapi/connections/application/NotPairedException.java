package bipo.tech.duoraapi.connections.application;

/**
 * Quem chama não formou par nessa rodada: ficou de fora, não estava no sorteio, ou a rodada ou o evento não
 * existem. A resposta é a mesma nos quatro casos.
 */
public class NotPairedException extends RuntimeException {

    public NotPairedException() {
        super("the caller has no partner in this round");
    }

}
