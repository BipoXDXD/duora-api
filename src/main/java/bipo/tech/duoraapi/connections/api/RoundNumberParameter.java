package bipo.tech.duoraapi.connections.api;

import bipo.tech.duoraapi.matching.Pairings;

/** O número da rodada na rota, validado na fronteira contra os limites que o matching publica. */
final class RoundNumberParameter {

    private RoundNumberParameter() {
    }

    /** @throws InvalidRequestException se o número está fora de 1 a 100 */
    static int validated(int number) {
        if (number < Pairings.FIRST_ROUND || number > Pairings.LAST_ROUND) {
            throw new InvalidRequestException(
                    "the round number must be between " + Pairings.FIRST_ROUND + " and " + Pairings.LAST_ROUND);
        }
        return number;
    }

}
