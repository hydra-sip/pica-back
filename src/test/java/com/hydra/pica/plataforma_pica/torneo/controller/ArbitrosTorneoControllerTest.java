package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.dto.ArbitroDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Árbitros del torneo (PICA-236): el organizador sale de la sesión y las reglas están en el servicio. */
@WebMvcTest(ArbitrosTorneoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class ArbitrosTorneoControllerTest {

    private static final Long ORGANIZADOR = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArbitrosTorneoService arbitrosService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Sin TORNEO_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(get("/api/v1/arbitros"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(arbitrosService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("POST responde 201 con Location y pasa los permisos")
    void designa() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(arbitrosService.designar(eq(ORGANIZADOR), eq(9L), any())).thenReturn(new ArbitroDetalle(7L, "juez",
                "Mario", "Gómez", EstadoDesignacion.PENDIENTE, Set.of(PermisoArbitro.CARGAR_RESULTADOS),
                Instant.parse("2026-10-26T12:00:00Z")));

        mockMvc.perform(post("/api/v1/torneos/9/arbitros")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuarioId": 7, "permisos": ["CARGAR_RESULTADOS"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/torneos/9/arbitros/7"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        ArgumentCaptor<DesignarArbitroRequest> captor = ArgumentCaptor.forClass(DesignarArbitroRequest.class);
        verify(arbitrosService).designar(eq(ORGANIZADOR), eq(9L), captor.capture());
        assertThat(captor.getValue().permisos()).containsExactly(PermisoArbitro.CARGAR_RESULTADOS);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Sin permisos responde 400 REQUERIDO sin llamar al servicio")
    void sinPermisosDelArbitro() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/torneos/9/arbitros")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usuarioId": 7, "permisos": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("permisos"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));
        verifyNoInteractions(arbitrosService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("DELETE responde 204 y la búsqueda pasa el texto")
    void quitaYBusca() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(arbitrosService.buscar("gomez", 0, 20)).thenReturn(Page.empty());

        mockMvc.perform(delete("/api/v1/torneos/9/arbitros/7")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/arbitros").param("q", "gomez")).andExpect(status().isOk());

        verify(arbitrosService).quitar(ORGANIZADOR, 9L, 7L);
        verify(arbitrosService).buscar("gomez", 0, 20);
    }
}
