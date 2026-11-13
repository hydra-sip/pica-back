package com.hydra.pica.plataforma_pica.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * El envío al webhook y sus reintentos (PICA-371), con Discord simulado y sin esperar de verdad: las esperas quedan
 * anotadas.
 */
class CanalDiscordTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/1234567890/aBc-DeF_ghij";

    private MockRestServiceServer discord;
    private CanalDiscord canal;
    private final List<Duration> esperas = new ArrayList<>();

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        discord = MockRestServiceServer.bindTo(builder).build();
        canal = new CanalDiscord(builder.build(), new SyncTaskExecutor(), Duration.ofSeconds(2), esperas::add);
    }

    @Test
    void publicaElTextoSinMenciones() {
        discord.expect(requestTo(WEBHOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("Hola @everyone"))
                .andExpect(jsonPath("$.allowed_mentions.parse").isEmpty())
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        canal.publicar(WEBHOOK, "Hola @everyone");

        discord.verify();
        assertThat(esperas).isEmpty();
    }

    @Test
    void sinWebhookNoHaceNada() {
        canal.publicar(null, "Hola");

        discord.verify();
    }

    @Test
    void con429EsperaLoQueDiceDiscordYReintenta() {
        discord.expect(requestTo(WEBHOOK))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\": \"You are being rate limited.\", \"retry_after\": 1.5, \"global\": false}"));
        discord.expect(requestTo(WEBHOOK)).andRespond(withSuccess());

        canal.publicar(WEBHOOK, "Hola");

        discord.verify();
        assertThat(esperas).containsExactly(Duration.ofMillis(1500));
    }

    @Test
    void con429SinCuerpoUsaLaCabeceraRetryAfter() {
        org.springframework.http.HttpHeaders cabeceras = new org.springframework.http.HttpHeaders();
        cabeceras.add("Retry-After", "3");
        discord.expect(requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(cabeceras));
        discord.expect(requestTo(WEBHOOK)).andRespond(withSuccess());

        canal.publicar(WEBHOOK, "Hola");

        discord.verify();
        assertThat(esperas).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void con5xxReintentaConEsperaCreciente() {
        discord.expect(times(2), requestTo(WEBHOOK)).andRespond(withServerError());
        discord.expect(requestTo(WEBHOOK)).andRespond(withSuccess());

        canal.publicar(WEBHOOK, "Hola");

        discord.verify();
        assertThat(esperas).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void conErrorDeRedReintenta() {
        discord.expect(requestTo(WEBHOOK)).andRespond(withException(new SocketTimeoutException("timeout")));
        discord.expect(requestTo(WEBHOOK)).andRespond(withSuccess());

        canal.publicar(WEBHOOK, "Hola");

        discord.verify();
        assertThat(esperas).hasSize(1);
    }

    @Test
    void con404NoReintenta() {
        discord.expect(times(1), requestTo(WEBHOOK)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        canal.publicar(WEBHOOK, "Hola");

        discord.verify();
        assertThat(esperas).isEmpty();
    }

    @Test
    void despuesDeCuatroIntentosLoDescartaSinRomper() {
        discord.expect(times(4), requestTo(WEBHOOK)).andRespond(withServerError());

        canal.publicar(WEBHOOK, "Hola");

        discord.verify();
        assertThat(esperas).hasSize(3);
    }

    @Test
    void cortaLosTextosQuePasanElLimiteDeDiscord() {
        discord.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.hasLength(CanalDiscord.LARGO_MAXIMO)))
                .andRespond(withSuccess());

        canal.publicar(WEBHOOK, "x".repeat(3000));

        discord.verify();
    }

}
