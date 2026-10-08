package bipo.tech.duoraapi.matching.application;

import bipo.tech.duoraapi.ActionRefusedException;
import bipo.tech.duoraapi.RefusalReason;

/** Rodadas só começam com o evento publicado e dentro do horário. */
public class EventNotUnderwayException extends ActionRefusedException {

    public EventNotUnderwayException() {
        super(RefusalReason.EVENT_NOT_UNDERWAY, "rounds start only while the event is published and underway");
    }

}
