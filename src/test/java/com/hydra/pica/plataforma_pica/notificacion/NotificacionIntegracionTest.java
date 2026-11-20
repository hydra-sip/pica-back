package com.hydra.pica.plataforma_pica.notificacion;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.notificacion.repository.NotificacionRepository;
import com.hydra.pica.plataforma_pica.notificacion.service.NotificacionService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Centro de notificaciones (HU-808, T808-1) contra Postgres: lo que guarda {@link NotificacionService}, el correo, y
 * GET /me/notificaciones con marcar leída y marcar todas. La entrega por WebSocket la prueba
 * {@link NotificacionesWebSocketIntegracionTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class NotificacionIntegracionTest {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private NotificacionService notificacionService;
    @Autowired private NotificacionRepository notificacionRepository;
    @Autowired private UsuarioService usuarioService;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JwtService jwtService;

    @MockitoBean private EmailService emailService;

    @Test
    @DisplayName("notificar guarda la notificación del usuario y le manda el correo con el enlace a la web")
    void notificarGuardaYMandaElCorreo() throws Exception {
        Usuario ana = usuario();

        notificacionService.notificar(ana.getId(), TipoNotificacion.CHECKIN_ABIERTO, "Check-in abierto",
                "Confirmá antes de las 18:30", "/torneos/copa", Map.of("partidoId", 7));

        pedir(get("/api/v1/me/notificaciones"), ana)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].tipo").value("CHECKIN_ABIERTO"))
                .andExpect(jsonPath("$.content[0].titulo").value("Check-in abierto"))
                .andExpect(jsonPath("$.content[0].cuerpo").value("Confirmá antes de las 18:30"))
                .andExpect(jsonPath("$.content[0].enlace").value("/torneos/copa"))
                .andExpect(jsonPath("$.content[0].datos.partidoId").value(7))
                .andExpect(jsonPath("$.content[0].leidaEn").doesNotExist())
                .andExpect(jsonPath("$.content[0].creadaEn").exists());
        verify(emailService).enviarAviso(eq(ana.getEmail()), eq("Check-in abierto"),
                contains("http://localhost:5173/torneos/copa"));
    }

    @Test
    @DisplayName("si el correo falla la notificación queda igual en el centro")
    void siElCorreoFallaQuedaIgual() throws Exception {
        Usuario ana = usuario();
        doThrow(new IllegalStateException("SMTP caído")).when(emailService).enviarAviso(anyString(), anyString(), anyString());

        notificacionService.notificar(ana.getId(), TipoNotificacion.CRUCE_INICIADO, "Empezó tu cruce", "A jugar", null, null);

        pedir(get("/api/v1/me/notificaciones"), ana)
                .andExpect(jsonPath("$.content[0].titulo").value("Empezó tu cruce"))
                .andExpect(jsonPath("$.content[0].datos").isEmpty());
    }

    @Test
    @DisplayName("cada uno ve solo las suyas, de la más nueva a la más vieja, y el contador sale de las no leídas")
    void listarSoloLasPropias() throws Exception {
        Usuario ana = usuario();
        Usuario beto = usuario();
        notificar(ana, "Primera");
        notificar(ana, "Segunda");
        notificar(beto, "De Beto");

        pedir(get("/api/v1/me/notificaciones"), ana)
                .andExpect(jsonPath("$.content[*].titulo").value(Matchers.contains("Segunda", "Primera")));
        pedir(get("/api/v1/me/notificaciones").param("leida", "false").param("size", "1"), ana)
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("el filtro leida separa las no leídas de las leídas; sin él vienen todas")
    void filtrarPorLeida() throws Exception {
        Usuario ana = usuario();
        notificar(ana, "Leída");
        pedir(post("/api/v1/me/notificaciones/" + idDeLaUltima(ana) + "/leida"), ana).andExpect(status().isNoContent());
        notificar(ana, "Sin leer");

        pedir(get("/api/v1/me/notificaciones").param("leida", "false"), ana)
                .andExpect(jsonPath("$.content[*].titulo").value(Matchers.contains("Sin leer")));
        pedir(get("/api/v1/me/notificaciones").param("leida", "true"), ana)
                .andExpect(jsonPath("$.content[*].titulo").value(Matchers.contains("Leída")));
        pedir(get("/api/v1/me/notificaciones"), ana)
                .andExpect(jsonPath("$.content[*].titulo").value(Matchers.contains("Sin leer", "Leída")));
    }

    @Test
    @DisplayName("marcar leída saca la notificación de las no leídas; la de otro usuario da 404")
    void marcarLeida() throws Exception {
        Usuario ana = usuario();
        Usuario beto = usuario();
        notificar(ana, "Rival asignado");
        Long id = idDeLaUltima(ana);

        pedir(post("/api/v1/me/notificaciones/" + id + "/leida"), beto)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NOTIFICACION_NO_ENCONTRADA"));
        pedir(post("/api/v1/me/notificaciones/" + id + "/leida"), ana).andExpect(status().isNoContent());
        // dos veces da lo mismo
        pedir(post("/api/v1/me/notificaciones/" + id + "/leida"), ana).andExpect(status().isNoContent());

        pedir(get("/api/v1/me/notificaciones").param("leida", "false"), ana)
                .andExpect(jsonPath("$.page.totalElements").value(0));
        pedir(get("/api/v1/me/notificaciones"), ana)
                .andExpect(jsonPath("$.content[0].leidaEn").exists());
    }

    @Test
    @DisplayName("marcar todas deja leídas las del usuario y no toca las de los demás")
    void marcarTodas() throws Exception {
        Usuario ana = usuario();
        Usuario beto = usuario();
        notificar(ana, "Una");
        notificar(ana, "Otra");
        notificar(beto, "De Beto");

        pedir(post("/api/v1/me/notificaciones/leidas"), ana).andExpect(status().isNoContent());

        pedir(get("/api/v1/me/notificaciones").param("leida", "false"), ana)
                .andExpect(jsonPath("$.page.totalElements").value(0));
        pedir(get("/api/v1/me/notificaciones").param("leida", "false"), beto)
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("sin sesión las tres operaciones responden 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/me/notificaciones")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/me/notificaciones/1/leida")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/me/notificaciones/leidas")).andExpect(status().isUnauthorized());
    }

    private void notificar(Usuario usuario, String titulo) {
        notificacionService.notificar(usuario.getId(), TipoNotificacion.RIVAL_ASIGNADO, titulo, "Cuerpo", "/torneos/copa", null);
    }

    private Long idDeLaUltima(Usuario usuario) {
        return notificacionRepository.findByUsuarioIdOrderByCreadaEnDescIdDesc(
                usuario.getId(), PageRequest.of(0, 1)).getContent().getFirst().getId();
    }

    private ResultActions pedir(MockHttpServletRequestBuilder pedido, Usuario usuario) throws Exception {
        String token = jwtService.generarAccessToken(usuario.getId(), usuario.getUsername(), List.of(), List.of(),
                usuario.getVersionSesion());
        return mockMvc.perform(pedido.header("Authorization", "Bearer " + token));
    }

    private Usuario usuario() {
        String username = "notif" + SECUENCIA.incrementAndGet();
        Persona persona = new Persona();
        persona.setNombres("Notif");
        persona.setApellidos("Prueba");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);
        return usuarioService.crear(NuevoUsuario.porAdmin(username, username + "@example.com", "Pica2026", null, null,
                persona.getId(), Set.of()));
    }
}
