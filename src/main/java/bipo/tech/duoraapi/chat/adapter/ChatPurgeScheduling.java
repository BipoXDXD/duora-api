package bipo.tech.duoraapi.chat.adapter;

import java.util.random.RandomGenerator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;

import bipo.tech.duoraapi.chat.application.ChatPurge;

/**
 * Agenda o expurgo do chat em todas as réplicas (docs/adr/0021), com desvio sorteado a cada execução, e expõe o
 * tamanho do que falta apagar. Não há eleição de líder nem lock entre réplicas: cada lote pula o que outra segura.
 * Um erro numa execução (banco fora, por exemplo) vai para o log pelo agendador, e a próxima tenta de novo.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatPurgeProperties.class)
class ChatPurgeScheduling implements SchedulingConfigurer {

    private final ChatPurge purge;
    private final ChatPurgeProperties properties;

    ChatPurgeScheduling(ChatPurge purge, ChatPurgeProperties properties) {
        this.purge = purge;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                () -> purge.purgeExpired(properties.batchSize(), properties.maxBatchesPerRun()),
                new JitteredDelayTrigger(properties.interval(), properties.jitter(), RandomGenerator.getDefault()));
    }

    /** Só a contagem, consultada na leitura da métrica pelo índice de purge_after. */
    @Bean
    MeterBinder chatPurgeBacklog() {
        return registry -> Gauge.builder(ChatPurge.BACKLOG_METRIC, purge, ChatPurge::expiredBacklog)
                .description("Chats past their purge time that were not deleted yet")
                .baseUnit("chats")
                .register(registry);
    }

}
