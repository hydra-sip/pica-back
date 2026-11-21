package com.hydra.pica.plataforma_pica.notificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.notificacion.service.NotificacionService;
import com.hydra.pica.plataforma_pica.realtime.dto.EventoTorneoMessage;
import com.hydra.pica.plataforma_pica.realtime.service.RealtimeNotifier;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * La cola de notificaciones por WebSocket (HU-808, T808-1) con clientes STOMP de verdad contra el servidor: cada
 * usuario recibe solo lo suyo, en menos de 5 segundos, y sin sesión o con un token que no vale no hay cola.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class NotificacionesWebSocketIntegracionTest {

    private static final String COLA = "/user/queue/notificaciones";
    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @LocalServerPort private int puerto;
    @Autowired private NotificacionService notificacionService;
    @Autowired private RealtimeNotifier realtimeNotifier;
    @Autowired private UsuarioService usuarioService;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private EmailService emailService;

    private final WebSocketStompClient cliente = new WebSocketStompClient(new StandardWebSocketClient());

    @AfterEach
    void cerrar() {
        cliente.stop();
    }

    @Test
    @DisplayName("la notificación le llega en menos de 5 segundos al usuario y no a otro conectado")
    void llegaSoloAlUsuario() throws Exception {
        Usuario ana = usuario();
        Usuario beto = usuario();
        Conexion deAna = conectar(token(ana));
        Conexion deBeto = conectar(token(beto));
        BlockingQueue<Map<String, Object>> paraAna = deAna.suscribir(COLA);
        BlockingQueue<Map<String, Object>> paraBeto = deBeto.suscribir(COLA);
        esperarSuscripciones();

        notificacionService.notificar(ana.getId(), TipoNotificacion.RIVAL_ASIGNADO, "Tu rival: Los Halcones",
                "Cuartos de final", "/torneos/copa", Map.of("partidoId", 3));

        Map<String, Object> recibida = paraAna.poll(5, TimeUnit.SECONDS);
        assertThat(recibida).isNotNull();
        assertThat(recibida).containsEntry("tipo", "RIVAL_ASIGNADO")
                .containsEntry("titulo", "Tu rival: Los Halcones")
                .containsEntry("enlace", "/torneos/copa")
                .containsKey("id");
        assertThat(paraBeto.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("sin token se conecta y sigue el bracket público, pero no se puede suscribir a la cola")
    void sinTokenNoHayCola() throws Exception {
        Conexion anonima = conectar(null);
        BlockingQueue<Map<String, Object>> bracket = anonima.suscribir("/topic/torneos/999");
        esperarSuscripciones();

        realtimeNotifier.publicarTorneo(999L, new EventoTorneoMessage(
                "ESTADO_CAMBIADO", 999L, EstadoTorneo.EN_CURSO, null, null, false, null, null));
        assertThat(bracket.poll(5, TimeUnit.SECONDS)).isNotNull();

        anonima.suscribir(COLA);
        assertThat(anonima.errores.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    @DisplayName("con sesión tampoco se puede pedir directo la cola ya resuelta de una sesión")
    void colaResueltaRechazada() throws Exception {
        Conexion deAna = conectar(token(usuario()));

        deAna.suscribir("/queue/notificaciones-user1");

        assertThat(deAna.errores.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    @DisplayName("un token que no vale corta la conexión")
    void tokenInvalido() {
        assertThatThrownBy(() -> conectar("no-es-un-token")).isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("el token de una sesión cerrada (otra versión de sesión) corta la conexión")
    void sesionCerrada() {
        Usuario ana = usuario();
        String viejo = jwtService.generarAccessToken(ana.getId(), ana.getUsername(), List.of(), List.of(),
                ana.getVersionSesion() + 1);

        assertThatThrownBy(() -> conectar(viejo)).isInstanceOf(ExecutionException.class);
    }

    // el broker registra la suscripción en otro hilo: sin esta espera el mensaje puede salir antes
    private static void esperarSuscripciones() throws InterruptedException {
        Thread.sleep(500);
    }

    private Conexion conectar(String token) throws Exception {
        MappingJackson2MessageConverter conversor = new MappingJackson2MessageConverter();
        conversor.setObjectMapper(objectMapper);
        cliente.setMessageConverter(conversor);

        StompHeaders cabeceras = new StompHeaders();
        if (token != null) {
            cabeceras.add("Authorization", "Bearer " + token);
        }
        Conexion conexion = new Conexion();
        conexion.sesion = cliente.connectAsync("ws://localhost:" + puerto + "/ws", new WebSocketHttpHeaders(),
                cabeceras, conexion.manejador).get(5, TimeUnit.SECONDS);
        return conexion;
    }

    private String token(Usuario usuario) {
        return jwtService.generarAccessToken(usuario.getId(), usuario.getUsername(), List.of(), List.of(),
                usuario.getVersionSesion());
    }

    private Usuario usuario() {
        String username = "notifws" + SECUENCIA.incrementAndGet();
        Persona persona = new Persona();
        persona.setNombres("Notif");
        persona.setApellidos("WebSocket");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);
        return usuarioService.crear(NuevoUsuario.porAdmin(username, username + "@example.com", "Pica2026", null, null,
                persona.getId(), Set.of()));
    }

    /** Una sesión STOMP y lo que el servidor le contestó con ERROR o al cortarla. */
    private static final class Conexion {

        private final BlockingQueue<Object> errores = new LinkedBlockingQueue<>();
        private StompSession sesion;

        private final StompSessionHandlerAdapter manejador = new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errores.add(payload == null ? StompCommand.ERROR : payload);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                errores.add(exception);
            }
        };

        private BlockingQueue<Map<String, Object>> suscribir(String destino) {
            BlockingQueue<Map<String, Object>> recibidos = new LinkedBlockingQueue<>();
            sesion.subscribe(destino, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                @SuppressWarnings("unchecked")
                public void handleFrame(StompHeaders headers, Object payload) {
                    recibidos.add((Map<String, Object>) payload);
                }
            });
            return recibidos;
        }
    }
}
