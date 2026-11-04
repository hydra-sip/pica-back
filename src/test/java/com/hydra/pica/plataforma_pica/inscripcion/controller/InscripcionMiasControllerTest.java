package com.hydra.pica.plataforma_pica.inscripcion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle.EquipoInscripto;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionResumen;
import com.hydra.pica.plataforma_pica.inscripcion.dto.TorneoDeInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Mis inscripciones (PICA-234): el usuario sale de la sesión y el filtro y el orden están en InscripcionService. */
@WebMvcTest(InscripcionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class InscripcionMiasControllerTest {

    private static final Long PARTICIPANTE = 5L;
    private static final TorneoDeInscripcion TORNEO = new TorneoDeInscripcion(3L, "Torneo Apertura F5", "torneo-apertura-f5");
    private static final Instant CREADA = Instant.parse("2026-10-24T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InscripcionService inscripcionService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser
    @DisplayName("GET /inscripciones/mias pasa torneoId y la página pedida al servicio")
    void pasaElFiltroYLaPagina() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.listarMias(eq(PARTICIPANTE), eq(3L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));

        mockMvc.perform(get("/api/v1/inscripciones/mias").param("torneoId", "3").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(inscripcionService).listarMias(eq(PARTICIPANTE), eq(3L), pagina.capture());
        assertThat(pagina.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pagina.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /inscripciones/mias sin parámetros trae la primera página de 20, sin filtro de torneo")
    void sinParametrosUsaLaPrimeraPaginaDe20() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.listarMias(eq(PARTICIPANTE), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/inscripciones/mias")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(inscripcionService).listarMias(eq(PARTICIPANTE), eq(null), pagina.capture());
        assertThat(pagina.getValue().getPageNumber()).isZero();
        assertThat(pagina.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("Sin autenticación responde 401")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/inscripciones/mias")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /inscripciones/mias alcanza con estar logueado y devuelve la página del usuario de la sesión")
    void devuelveLaPaginaDelUsuarioDeLaSesion() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.listarMias(eq(PARTICIPANTE), eq(null), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new InscripcionResumen(7L, 3L, TORNEO, null, EstadoInscripcion.PENDIENTE_PAGO, CREADA, CREADA,
                        new EquipoInscripto(9L, "Los Halcones")),
                new InscripcionResumen(6L, 2L, TORNEO, PARTICIPANTE, EstadoInscripcion.BORRADOR, CREADA, CREADA, null)),
                PageRequest.of(0, 20), 2));

        mockMvc.perform(get("/api/v1/inscripciones/mias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].torneoId").value(3))
                .andExpect(jsonPath("$.content[0].torneo.nombre").value("Torneo Apertura F5"))
                .andExpect(jsonPath("$.content[0].torneo.slug").value("torneo-apertura-f5"))
                .andExpect(jsonPath("$.content[0].usuarioId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE_PAGO"))
                .andExpect(jsonPath("$.content[0].equipo.id").value(9))
                .andExpect(jsonPath("$.content[0].equipo.nombre").value("Los Halcones"))
                .andExpect(jsonPath("$.content[0].nomina").doesNotExist())
                .andExpect(jsonPath("$.content[1].usuarioId").value(PARTICIPANTE))
                .andExpect(jsonPath("$.content[1].equipo").value(nullValue()))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }
}
