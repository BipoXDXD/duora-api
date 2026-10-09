package bipo.tech.duoraapi.chat.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TriggerTask;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/** O expurgo do chat entra no agendador de cada réplica, com o atraso sorteado a cada execução (docs/adr/0021). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatPurgeSchedulingIT {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Test
    void thePurgeIsScheduledWithJitter() {
        assertThat(scheduledTasks.getScheduledTasks())
                .map(scheduled -> scheduled.getTask())
                .filteredOn(TriggerTask.class::isInstance)
                .map(task -> ((TriggerTask) task).getTrigger())
                .hasExactlyElementsOfTypes(JitteredDelayTrigger.class);
    }

}
