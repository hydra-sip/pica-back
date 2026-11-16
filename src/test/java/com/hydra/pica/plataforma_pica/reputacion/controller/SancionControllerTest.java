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
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionRequest;
import com.hydra.pica.plataforma_pica.reputacion.service.SancionService;
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

@WebMvcTest(SancionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class SancionControllerTest {

    private static final Long ORGANIZADOR = 1L;
    private static final SancionDetalle SANCION = new SancionDetalle(7L, 9L, "Copa", new UsuarioPublico(3L, "juan",
            "Juan", "Uno"), null, Gravedad.MEDIA, "Juego brusco", new UsuarioPublico(ORGANIZADOR, "olga", "Olga",
            "Organizadora"), Instant.parse("2026-11-15T18:00:00Z"), null);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SancionService sancionService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/torneos/9/sanciones")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sanciones/mias")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sancionService);
    }

    @Test
    @WithMockUser
    @DisplayName("Una sanción válida responde 201 con lo registrado")
    void aplicar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(sancionService.aplicar(ORGANIZADOR, 9L, new SancionRequest(3L, null, Gravedad.MEDIA, "Juego brusco")))
                .thenReturn(SANCION);

        mockMvc.perform(post("/api/v1/torneos/9/sanciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":3,\"gravedad\":\"MEDIA\",\"motivo\":\"Juego brusco\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/torneos/9/sanciones"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.jugador.username").value("juan"))
                .andExpect(jsonPath("$.gravedad").value("MEDIA"))
                .andExpect(jsonPath("$.aplicadaPor.id").value(1))
                .andExpect(jsonPath("$.partidoId").isEmpty());
    }

    @Test
    @WithMockUser
    @DisplayName("Sin motivo ni gravedad responde 400 VALIDACION sin llegar al servicio")
    void sinMotivo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/torneos/9/sanciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":3,\"motivo\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'motivo')]").exists())
                .andExpect(jsonPath("$.errores[?(@.campo == 'gravedad')]").exists());
        verifyNoInteractions(sancionService);
    }

    @Test
    @WithMockUser
    @DisplayName("Quien no organiza ni arbitra recibe 403 y un jugador ajeno 409")
    void errores() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(5L));
        when(sancionService.aplicar(eq(5L), eq(9L), any()))
                .thenThrow(new ProhibidoException(CodigoError.SIN_PERMISO, "no"));
        when(sancionService.aplicar(eq(5L), eq(10L), any()))
                .thenThrow(new ConflictoException(CodigoError.JUGADOR_NO_PARTICIPA, "no juega"));
        String cuerpo = "{\"usuarioId\":3,\"gravedad\":\"LEVE\",\"motivo\":\"Insultó\"}";

        mockMvc.perform(post("/api/v1/torneos/9/sanciones").contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(post("/api/v1/torneos/10/sanciones").contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("JUGADOR_NO_PARTICIPA"));
    }

    @Test
    @WithMockUser
    @DisplayName("Las del torneo y las propias responden 200")
    void listas() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(3L));
        when(sancionService.delTorneo(9L)).thenReturn(List.of(SANCION));
        when(sancionService.mias(3L)).thenReturn(List.of(SANCION));

        mockMvc.perform(get("/api/v1/torneos/9/sanciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].motivo").value("Juego brusco"));
        mockMvc.perform(get("/api/v1/sanciones/mias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].torneo").value("Copa"));
    }
}
