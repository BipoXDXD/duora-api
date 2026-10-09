package bipo.tech.duoraapi.chat.domain;

/**
 * O que os outros módulos dizem sobre o chat no instante do envio ou da leitura (docs/adr/0021). O estado
 * aberto é calculado na hora a partir disso, nunca guardado: nenhum job fecha chats.
 *
 * @param eventUnderway o evento está publicado e dentro do horário
 * @param roundIsCurrent a rodada do chat é a última iniciada no evento
 * @param separatedByBlock uma das duas pessoas bloqueou a outra, em qualquer direção
 */
public record OpeningConditions(boolean eventUnderway, boolean roundIsCurrent, boolean separatedByBlock) {

    boolean allowSending() {
        return eventUnderway && roundIsCurrent && !separatedByBlock;
    }

}
