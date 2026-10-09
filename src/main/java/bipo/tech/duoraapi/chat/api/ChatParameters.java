package bipo.tech.duoraapi.chat.api;

import bipo.tech.duoraapi.chat.domain.Chat;
import bipo.tech.duoraapi.config.MaxPageSize;
import bipo.tech.duoraapi.matching.Pairings;

/** Os números da rota e da query, validados na fronteira contra os limites do matching e do chat. */
final class ChatParameters {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 100;

    private ChatParameters() {
    }

    /** @throws InvalidRequestException se o número está fora de 1 a 100 */
    static int roundNumber(int number) {
        if (number < Pairings.FIRST_ROUND || number > Pairings.LAST_ROUND) {
            throw new InvalidRequestException(
                    "the round number must be between " + Pairings.FIRST_ROUND + " and " + Pairings.LAST_ROUND);
        }
        return number;
    }

    /** @throws InvalidRequestException se a posição está fora de 1 a 300 */
    static int seq(int seq) {
        if (seq < 1 || seq > Chat.MAX_MESSAGES) {
            throw new InvalidRequestException("seq must be between 1 and " + Chat.MAX_MESSAGES);
        }
        return seq;
    }

    /**
     * Ausente vale 0, o começo do chat. Lido como texto porque o Spring trata {@code afterSeq=} vazio como
     * ausente, e o contrato o recusa como qualquer valor que não é inteiro.
     *
     * @throws InvalidRequestException fora de 0 a 300
     */
    static int afterSeq(String text) {
        if (text == null) {
            return 0;
        }
        try {
            int afterSeq = Integer.parseInt(text);
            if (afterSeq >= 0 && afterSeq <= Chat.MAX_MESSAGES) {
                return afterSeq;
            }
        } catch (NumberFormatException e) {
            // cai no 400 abaixo, como um número fora da faixa
        }
        throw new InvalidRequestException("afterSeq must be between 0 and " + Chat.MAX_MESSAGES);
    }

    /** @throws bipo.tech.duoraapi.config.InvalidPageParameterException fora de 1 ao teto; ausente vale o padrão */
    static int maxPageSize(String text) {
        return MaxPageSize.parse(text, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
    }

}
