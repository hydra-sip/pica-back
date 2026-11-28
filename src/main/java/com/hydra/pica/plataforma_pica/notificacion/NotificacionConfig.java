package com.hydra.pica.plataforma_pica.notificacion;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class NotificacionConfig {

    /**
     * Las notificaciones se entregan fuera del hilo del pedido: el correo puede tardar y no tiene que frenar a quien
     * generó el evento. Los tests lo hacen sincrónico ({@code app.notificaciones.sincronico}), como los avisos a Discord.
     */
    @Bean
    Executor notificacionExecutor(@Value("${app.notificaciones.sincronico:false}") boolean sincronico) {
        if (sincronico) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setThreadNamePrefix("notificacion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
