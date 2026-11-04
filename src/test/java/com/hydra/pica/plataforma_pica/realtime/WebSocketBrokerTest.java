package com.hydra.pica.plataforma_pica.realtime;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WebSocketBrokerTest.TestConfig.class)
class WebSocketBrokerTest {

    @Configuration
    @Import(WebSocketConfig.class)
    static class TestConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public RealtimeNotifier realtimeNotifier(org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate) {
            return new RealtimeNotifier(messagingTemplate);
        }
    }

    @Autowired
    private RealtimeNotifier realtimeNotifier;

    @Autowired
    @Qualifier("brokerChannel")
    private SubscribableChannel brokerChannel;

    @Test
    @DisplayName("Publicar en /topic/torneos/{id} envía el mensaje con el destino correcto al broker STOMP")
    void publicarTorneoLlegaAlBroker() throws Exception {
        BlockingQueue<Message<?>> recibidos = new LinkedBlockingQueue<>();
        MessageHandler handler = recibidos::add;
        brokerChannel.subscribe(handler);

        try {
            EventoTorneoMessage mensaje = new EventoTorneoMessage(
                    "RESULTADO_CONFIRMADO",
                    42L,
                    EstadoTorneo.EN_CURSO,
                    10L,
                    100L,
                    false,
                    new BracketTorneo(42L, "seed", "hash", List.of()),
                    new BigDecimal("50000.00")
            );

            realtimeNotifier.publicarTorneo(42L, mensaje);

            Message<?> msg = recibidos.poll(5, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();

            StompHeaderAccessor headers = StompHeaderAccessor.wrap(msg);
            assertThat(headers.getDestination()).isEqualTo("/topic/torneos/42");
            assertThat(msg.getPayload()).isInstanceOf(byte[].class);
            String payloadStr = new String((byte[]) msg.getPayload());
            assertThat(payloadStr).contains("RESULTADO_CONFIRMADO");
            assertThat(payloadStr).contains("\"torneoId\":42");
            assertThat(payloadStr).contains("50000.00");
        } finally {
            brokerChannel.unsubscribe(handler);
        }
    }

    @Test
    @DisplayName("Publicar en /topic/rifas/{id} envía el mensaje con el destino correcto al broker STOMP")
    void publicarRifaLlegaAlBroker() throws Exception {
        BlockingQueue<Message<?>> recibidos = new LinkedBlockingQueue<>();
        MessageHandler handler = recibidos::add;
        brokerChannel.subscribe(handler);

        try {
            EventoRifaMessage mensaje = new EventoRifaMessage(
                    "NUMEROS_VENDIDOS",
                    88L,
                    42L,
                    EstadoRifa.EN_VENTA,
                    List.of(1, 2, 3),
                    15,
                    new BigDecimal("1500.00"),
                    new BigDecimal("150.00")
            );

            realtimeNotifier.publicarRifa(88L, mensaje);

            Message<?> msg = recibidos.poll(5, TimeUnit.SECONDS);
            assertThat(msg).isNotNull();

            StompHeaderAccessor headers = StompHeaderAccessor.wrap(msg);
            assertThat(headers.getDestination()).isEqualTo("/topic/rifas/88");
            String payloadStr = new String((byte[]) msg.getPayload());
            assertThat(payloadStr).contains("NUMEROS_VENDIDOS");
            assertThat(payloadStr).contains("\"rifaId\":88");
            assertThat(payloadStr).contains("1500.00");
        } finally {
            brokerChannel.unsubscribe(handler);
        }
    }
}
