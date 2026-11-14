package com.hydra.pica.plataforma_pica.discord;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Publica un mensaje en el canal de Discord de un torneo por su webhook (PICA-369). El envío va en el executor de
 * Discord, de un solo hilo para que los mensajes salgan en orden: quien lo llama no espera a Discord, y si Discord
 * falla, lo que lo disparó queda igual.
 *
 * Sin menciones ({@code allowed_mentions} vacío): un equipo que se llame «@everyone» no puede pinguear al canal.
 */
@Slf4j
@Component
public class CanalDiscord {

    private final RestClient http;
    private final Executor executor;

    CanalDiscord(@Qualifier("discordHttp") RestClient http, @Qualifier("discordExecutor") Executor executor) {
        this.http = http;
        this.executor = executor;
    }

    /** Sin webhook no hace nada. */
    public void publicar(String webhook, String texto) {
        if (webhook == null) {
            return;
        }
        executor.execute(() -> entregar(webhook, texto));
    }

    private void entregar(String webhook, String texto) {
        try {
            http.post()
                    .uri(URI.create(webhook))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", texto, "allowed_mentions", Map.of("parse", List.of())))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("No se pudo publicar en el webhook de Discord {}: {}", WebhookDiscord.enmascarar(webhook),
                    e.getMessage());
        }
    }
}
