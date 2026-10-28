package com.hydra.pica.plataforma_pica.realtime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.common.config.WebSocketConfig;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoRifaMessage;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import com.hydra.pica.plataforma_pica.realtime.service.RealtimeNotifier;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba de RNF-TRE-01 (T305-3).
 *
 * Requisito no funcional:
 *   - Dos clientes conectados concurrentemente
 *   - 100 repeticiones
 *   - Percentil 95 (p95) de latencia de entrega de 5 segundos o menos (5000 ms)
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RnfTre01Test.TestConfig.class)
public class RnfTre01Test {

    public static final int REPETICIONES = 100;
    public static final double TOPE_P95_SEGUNDOS = 5.0;
    public static final double TOPE_P95_MS = TOPE_P95_SEGUNDOS * 1000.0;

    @Configuration
    @Import(WebSocketConfig.class)
    static class TestConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public RealtimeNotifier realtimeNotifier(SimpMessagingTemplate messagingTemplate) {
            return new RealtimeNotifier(messagingTemplate);
        }
    }

    @Autowired
    private RealtimeNotifier realtimeNotifier;

    @Autowired
    @Qualifier("clientInboundChannel")
    private SubscribableChannel clientInboundChannel;

    @Autowired
    @Qualifier("clientOutboundChannel")
    private SubscribableChannel clientOutboundChannel;

    @Test
    @DisplayName("RNF-TRE-01: Dos clientes conectados a /topic/torneos/{id}, 100 repeticiones, p95 <= 5s")
    void pruebaRnfTre01Torneos() throws Exception {
        String session1 = "cliente-ws-1";
        String session2 = "cliente-ws-2";
        String topic = "/topic/torneos/101";

        // Conectar y suscribir ambos clientes al topic de torneos
        conectarYSubscribir(session1, "sub-torneo-1", topic);
        conectarYSubscribir(session2, "sub-torneo-2", topic);

        List<Double> latenciasMs = new ArrayList<>();

        for (int i = 1; i <= REPETICIONES; i++) {
            CountDownLatch latch = new CountDownLatch(2);
            AtomicLong recibidoCliente1 = new AtomicLong(0);
            AtomicLong recibidoCliente2 = new AtomicLong(0);

            MessageHandler handler = message -> {
                SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
                if (SimpMessageType.MESSAGE.equals(accessor.getMessageType()) && topic.equals(accessor.getDestination())) {
                    long now = System.nanoTime();
                    if (session1.equals(accessor.getSessionId())) {
                        recibidoCliente1.set(now);
                        latch.countDown();
                    } else if (session2.equals(accessor.getSessionId())) {
                        recibidoCliente2.set(now);
                        latch.countDown();
                    }
                }
            };

            clientOutboundChannel.subscribe(handler);

            try {
                EventoTorneoMessage mensaje = new EventoTorneoMessage(
                        "RESULTADO_CONFIRMADO",
                        101L,
                        EstadoTorneo.EN_CURSO,
                        (long) i,
                        1000L + i,
                        false,
                        new BracketTorneo(101L, "seed-" + i, "hash-" + i, List.of()),
                        new BigDecimal("150000.00")
                );

                long inicio = System.nanoTime();
                realtimeNotifier.publicarTorneo(101L, mensaje);

                boolean llegaronAmbos = latch.await(5, TimeUnit.SECONDS);
                if (!llegaronAmbos) {
                    throw new AssertionError("La repetición " + i + " no entregó el mensaje a ambos clientes dentro de 5 segundos");
                }

                long fin = Math.max(recibidoCliente1.get(), recibidoCliente2.get());
                double latenciaMs = (fin - inicio) / 1_000_000.0;
                latenciasMs.add(latenciaMs);
            } finally {
                clientOutboundChannel.unsubscribe(handler);
            }
        }

        imprimirYValidarInforme("Torneo (/topic/torneos/101)", latenciasMs);
    }

    @Test
    @DisplayName("RNF-TRE-01: Dos clientes conectados a /topic/rifas/{id}, 100 repeticiones, p95 <= 5s")
    void pruebaRnfTre01Rifas() throws Exception {
        String session1 = "cliente-rifa-1";
        String session2 = "cliente-rifa-2";
        String topic = "/topic/rifas/55";

        // Conectar y suscribir ambos clientes al topic de rifas
        conectarYSubscribir(session1, "sub-rifa-1", topic);
        conectarYSubscribir(session2, "sub-rifa-2", topic);

        List<Double> latenciasMs = new ArrayList<>();

        for (int i = 1; i <= REPETICIONES; i++) {
            CountDownLatch latch = new CountDownLatch(2);
            AtomicLong recibidoCliente1 = new AtomicLong(0);
            AtomicLong recibidoCliente2 = new AtomicLong(0);

            MessageHandler handler = message -> {
                SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
                if (SimpMessageType.MESSAGE.equals(accessor.getMessageType()) && topic.equals(accessor.getDestination())) {
                    long now = System.nanoTime();
                    if (session1.equals(accessor.getSessionId())) {
                        recibidoCliente1.set(now);
                        latch.countDown();
                    } else if (session2.equals(accessor.getSessionId())) {
                        recibidoCliente2.set(now);
                        latch.countDown();
                    }
                }
            };

            clientOutboundChannel.subscribe(handler);

            try {
                EventoRifaMessage mensaje = new EventoRifaMessage(
                        "NUMEROS_VENDIDOS",
                        55L,
                        101L,
                        EstadoRifa.EN_VENTA,
                        List.of(i),
                        i,
                        new BigDecimal(i * 100),
                        new BigDecimal(i * 10)
                );

                long inicio = System.nanoTime();
                realtimeNotifier.publicarRifa(55L, mensaje);

                boolean llegaronAmbos = latch.await(5, TimeUnit.SECONDS);
                if (!llegaronAmbos) {
                    throw new AssertionError("La repetición " + i + " no entregó el mensaje a ambos clientes dentro de 5 segundos");
                }

                long fin = Math.max(recibidoCliente1.get(), recibidoCliente2.get());
                double latenciaMs = (fin - inicio) / 1_000_000.0;
                latenciasMs.add(latenciaMs);
            } finally {
                clientOutboundChannel.unsubscribe(handler);
            }
        }

        imprimirYValidarInforme("Rifa (/topic/rifas/55)", latenciasMs);
    }

    private void conectarYSubscribir(String sessionId, String subId, String destination) {
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId(sessionId);
        connect.setLeaveMutable(true);
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()));

        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setSessionId(sessionId);
        subscribe.setSubscriptionId(subId);
        subscribe.setDestination(destination);
        subscribe.setLeaveMutable(true);
        clientInboundChannel.send(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));
    }

    private void imprimirYValidarInforme(String caso, List<Double> latencias) {
        assertThat(latencias).hasSize(REPETICIONES);
        Collections.sort(latencias);

        double min = latencias.get(0);
        double max = latencias.get(latencias.size() - 1);
        double sum = 0.0;
        for (double d : latencias) {
            sum += d;
        }
        double avg = sum / latencias.size();

        double p50 = latencias.get((int) (REPETICIONES * 0.50) - 1);
        double p90 = latencias.get((int) (REPETICIONES * 0.90) - 1);
        double p95 = latencias.get((int) Math.ceil(REPETICIONES * 0.95) - 1);
        double p99 = latencias.get((int) Math.ceil(REPETICIONES * 0.99) - 1);

        System.out.println();
        System.out.println("================================================================================");
        System.out.println(" PRUEBA RNF-TRE-01: ACTUALIZACIÓN EN TIEMPO REAL - " + caso.toUpperCase());
        System.out.println("================================================================================");
        System.out.printf(" Clientes conectados:     2 concurrentes%n");
        System.out.printf(" Repeticiones:            %d%n", REPETICIONES);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf(" Latencia mínima:         %.3f ms%n", min);
        System.out.printf(" Latencia promedio:       %.3f ms%n", avg);
        System.out.printf(" Percentil 50 (p50):      %.3f ms%n", p50);
        System.out.printf(" Percentil 90 (p90):      %.3f ms%n", p90);
        System.out.printf(" Percentil 95 (p95):      %.3f ms  (Límite exigido: %.1f ms / %.1f s)%n", p95, TOPE_P95_MS, TOPE_P95_SEGUNDOS);
        System.out.printf(" Percentil 99 (p99):      %.3f ms%n", p99);
        System.out.printf(" Latencia máxima:         %.3f ms%n", max);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf(" RESULTADO: %s (p95 = %.3f ms <= %.1f ms)%n",
                p95 <= TOPE_P95_MS ? "CUMPLE RNF-TRE-01 ✓" : "FALLA RNF-TRE-01 ✗", p95, TOPE_P95_MS);
        System.out.println("================================================================================");
        System.out.println();

        assertThat(p95)
                .as("El percentil 95 (p95) debe ser de 5 segundos (5000 ms) o menos según RNF-TRE-01")
                .isLessThanOrEqualTo(TOPE_P95_MS);
    }
}
