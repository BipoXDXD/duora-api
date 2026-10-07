package bipo.tech.duoraapi.matching.application;

/**
 * A rodada não existe, o evento não existe ou a pessoa não estava no sorteio: a mesma resposta nos três
 * casos, para não revelar rodadas de eventos alheios.
 */
public class SeatNotFoundException extends RuntimeException {

    public SeatNotFoundException() {
        super("no seat for you in this round");
    }

}
