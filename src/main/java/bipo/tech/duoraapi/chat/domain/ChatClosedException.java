package bipo.tech.duoraapi.chat.domain;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/**
 * O chat não aceita mais mensagens. Um motivo só para todos os casos (rodada seguinte, fim do evento,
 * bloqueio, limite de mensagens), para quem foi bloqueado não descobrir o bloqueio (docs/adr/0015, 0021).
 */
public class ChatClosedException extends ActionRefusedException {

    public ChatClosedException() {
        super(RefusalReason.CHAT_CLOSED, "the chat is closed");
    }

}
