package com.hydra.pica.plataforma_pica.discord;

import java.time.Duration;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

/** El cliente HTTP y el executor de los avisos a Discord (PICA-369). */
@Configuration
class DiscordConfig {

    @Bean
    RestClient discordHttp(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(5));
        fabrica.setReadTimeout(Duration.ofSeconds(10));
        return builder.clone().requestFactory(fabrica).build();
    }

    /**
     * Un solo hilo, para que los mensajes salgan en el orden en que pasaron las cosas. Los tests lo hacen sincrónico
     * ({@code app.discord.sincronico}) para ver lo que llegó a Discord sin esperar.
     */
    @Bean
    Executor discordExecutor(@Value("${app.discord.sincronico:false}") boolean sincronico) {
        if (sincronico) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("discord-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
