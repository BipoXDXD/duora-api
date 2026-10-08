package bipo.tech.duoraapi.events;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * O que o events precisa saber das rodadas de um evento para mostrá-lo (docs/adr/0017): só o número da
 * última iniciada. As rodadas são do matching, que implementa esta interface; ela mora aqui para a
 * dependência entre os módulos continuar só de matching para events, sem ciclo.
 */
public interface RoundProgress {

    /** O número da última rodada iniciada no evento, ou vazio se nenhuma começou (ou o evento não existe). */
    OptionalInt latestStartedRoundOf(UUID eventId);

}
