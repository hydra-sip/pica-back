package com.hydra.pica.plataforma_pica.discord;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Publica un mensaje en el canal de Discord de un torneo por su webhook (PICA-369). El envío va en el executor de
 * Discord, de un solo hilo para que los mensajes salgan en orden: quien lo llama no espera a Discord, y si Discord
 * falla, lo que lo disparó queda igual.
 *
 * <ul>
 * <li>Sin menciones ({@code allowed_mentions} vacío): un equipo que se llame «@everyone» no puede pinguear al
 * canal.</li>
 * <li>Con 429 espera lo que pide Discord ({@code retry_after}, o la cabecera {@code Retry-After}); con 5xx o un error de
 * red, una espera que se duplica en cada intento. Hasta {@value #INTENTOS} intentos; después, un warn y se descarta.
 * Mientras espera, los mensajes que siguen esperan detrás.</li>
 * <li>Con otro 4xx (el webhook se borró en Discord, 401) no reintenta: warn y se descarta.</li>
 * <li>Lo pendiente vive en memoria: si se reinicia el servidor, se pierde.</li>
 * </ul>
 */
@Slf4j
@Component
public class CanalDiscord {

    /** El límite de {@code content} en Discord. */
    public static final int LARGO_MAXIMO = 2000;
    static final int INTENTOS = 4;
    private static final Duration ESPERA_MAXIMA = Duration.ofSeconds(60);

    /** Para que los tests anoten las esperas en lugar de dormir. */
    interface Espera {
        void esperar(Duration duracion) throws InterruptedException;
    }

    private final RestClient http;
    private final Executor executor;
    private final Duration esperaBase;
    private final Espera espera;

    @Autowired
    CanalDiscord(@Qualifier("discordHttp") RestClient http, @Qualifier("discordExecutor") Executor executor,
                 @Value("${app.discord.espera-base:2s}") Duration esperaBase) {
        this(http, executor, esperaBase, d -> Thread.sleep(d.toMillis()));
    }

    CanalDiscord(RestClient http, Executor executor, Duration esperaBase, Espera espera) {
        this.http = http;
        this.executor = executor;
        this.esperaBase = esperaBase;
        this.espera = espera;
    }

    /** Sin webhook no hace nada. Un texto que pasa el límite de Discord se corta. */
    public void publicar(String webhook, String texto) {
        if (webhook == null) {
            return;
        }
        String contenido = texto.length() <= LARGO_MAXIMO ? texto : texto.substring(0, LARGO_MAXIMO - 1) + "…";
        executor.execute(() -> entregar(webhook, contenido));
    }

    private void entregar(String webhook, String texto) {
        for (int intento = 1; ; intento++) {
            Duration pausa;
            try {
                http.post()
                        .uri(URI.create(webhook))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("content", texto, "allowed_mentions", Map.of("parse", List.of())))
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (HttpClientErrorException.TooManyRequests e) {
                pausa = retryAfter(e);
            } catch (HttpClientErrorException e) {
                log.warn("Discord rechazó el aviso al webhook {} ({}): se descarta", WebhookDiscord.enmascarar(webhook),
                        e.getStatusCode());
                return;
            } catch (RestClientException e) {
                pausa = esperaBase.multipliedBy(1L << (intento - 1));
            }
            if (intento == INTENTOS) {
                log.warn("No se pudo publicar en el webhook de Discord {} después de {} intentos: se descarta",
                        WebhookDiscord.enmascarar(webhook), INTENTOS);
                return;
            }
            try {
                espera.esperar(pausa.compareTo(ESPERA_MAXIMA) > 0 ? ESPERA_MAXIMA : pausa);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Discord manda {@code retry_after} en segundos (con decimales) en el cuerpo, y también la cabecera. */
    private Duration retryAfter(HttpClientErrorException e) {
        try {
            Map<?, ?> cuerpo = e.getResponseBodyAs(Map.class);
            if (cuerpo != null && cuerpo.get("retry_after") instanceof Number segundos) {
                return Duration.ofMillis(Math.round(segundos.doubleValue() * 1000));
            }
        } catch (RuntimeException ignorada) {
            // sin cuerpo JSON: queda la cabecera
        }
        String cabecera = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (cabecera != null) {
            try {
                return Duration.ofMillis(Math.round(Double.parseDouble(cabecera) * 1000));
            } catch (NumberFormatException ignorada) {
                // un formato que no esperamos: la espera base
            }
        }
        return esperaBase;
    }
}
