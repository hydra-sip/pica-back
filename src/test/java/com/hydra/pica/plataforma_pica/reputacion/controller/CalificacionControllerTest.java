package com.hydra.pica.plataforma_pica.reputacion.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.CalificacionRequest;
import com.hydra.pica.plataforma_pica.reputacion.dto.JugadorCalificable;
import com.hydra.pica.plataforma_pica.reputacion.service.CalificacionService;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CalificacionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class CalificacionControllerTest {

    private static final Long ORGANIZADOR = 1L;
    private static final UsuarioPublico JUGADOR = new UsuarioPublico(3L, "juan", "Juan", "Uno");
    private static final CalificacionDetalle CALIFICACION = new CalificacionDetalle(7L, 9L, "Copa", JUGADOR,
            new UsuarioPublico(ORGANIZADOR, "olga", "Olga", "Organizadora"), 4, "Puntual", true,
            Instant.parse("2026-11-15T18:00:00Z"));

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CalificacionService calificacionService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/torneos/9/calificaciones")).andExpect(status().isUnauthorized());
        verifyNoInteractions(calificacionService);
    }

    @Test
    @WithMockUser
    @DisplayName("Una calificación válida responde 201 con lo registrado")
    void calificar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(calificacionService.calificar(ORGANIZADOR, 9L, new CalificacionRequest(3L, 4, "Puntual", true)))
                .thenReturn(CALIFICACION);

        mockMvc.perform(post("/api/v1/torneos/9/calificaciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":3,\"puntaje\":4,\"comentario\":\"Puntual\",\"noConfiable\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/torneos/9/calificaciones"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.jugador.username").value("juan"))
                .andExpect(jsonPath("$.organizador.id").value(1))
                .andExpect(jsonPath("$.puntaje").value(4))
                .andExpect(jsonPath("$.noConfiable").value(true))
                .andExpect(jsonPath("$.creadaEn").value("2026-11-15T18:00:00Z"));
    }

    @Test
    @WithMockUser
    @DisplayName("Un puntaje fuera de 1 a 5 responde 400 VALIDACION sin llegar al servicio")
    void puntajeInvalido() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/torneos/9/calificaciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":3,\"puntaje\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'puntaje')]").exists());
        verifyNoInteractions(calificacionService);
    }

    @Test
    @WithMockUser
    @DisplayName("Calificar dos veces al mismo jugador responde 409 JUGADOR_YA_CALIFICADO")
    void yaCalificado() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(calificacionService.calificar(eq(ORGANIZADOR), eq(9L), any()))
                .thenThrow(new ConflictoException(CodigoError.JUGADOR_YA_CALIFICADO, "ya calificado"));

        mockMvc.perform(post("/api/v1/torneos/9/calificaciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":3,\"puntaje\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("JUGADOR_YA_CALIFICADO"));
    }

    @Test
    @WithMockUser
    @DisplayName("Los calificables traen la calificación de los ya calificados")
    void calificables() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(calificacionService.calificables(ORGANIZADOR, 9L)).thenReturn(List.of(
                new JugadorCalificable(31L, null, JUGADOR, CALIFICACION),
                new JugadorCalificable(32L, "Los Rudos", new UsuarioPublico(4L, "ana", "Ana", "Dos"), null)));

        mockMvc.perform(get("/api/v1/torneos/9/calificaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].calificacion.puntaje").value(4))
                .andExpect(jsonPath("$[1].equipo").value("Los Rudos"))
                .andExpect(jsonPath("$[1].calificacion").isEmpty());
    }
}
