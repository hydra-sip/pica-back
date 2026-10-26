package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.OrganizadorResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.PremioDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoPublicoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TorneoPublicoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class TorneoPublicoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TorneoPublicoService torneoPublicoService;

    @Test
    @DisplayName("GET /api/v1/torneos/resumen es público y devuelve la página con filtros")
    void listarPublicoSinAutenticacion() throws Exception {
        TorneoResumen resumen = new TorneoResumen(
                1L,
                "copa-verano",
                "Copa de Verano",
                "Torneo abierto",
                "Fútbol 5",
                Formato.ELIMINACION_DIRECTA,
                Modalidad.PRESENCIAL,
                TipoParticipante.EQUIPO,
                "Sede Central",
                EstadoTorneo.INSCRIPCION_ABIERTA,
                16,
                12,
                new BigDecimal("5000.00"),
                "ARS",
                new BigDecimal("56000.00"),
                Instant.parse("2026-11-01T12:00:00Z"),
                Instant.parse("2026-11-05T10:00:00Z"),
                null);

        when(torneoPublicoService.listarPublicos(eq("Fútbol 5"), eq(EstadoTorneo.INSCRIPCION_ABIERTA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(resumen), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/torneos/resumen")
                        .param("disciplina", "Fútbol 5")
                        .param("estado", "INSCRIPCION_ABIERTA")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("copa-verano"))
                .andExpect(jsonPath("$.content[0].cupoDisponible").value(12))
                .andExpect(jsonPath("$.content[0].pozoEstimado").value(56000.00))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        verify(torneoPublicoService).listarPublicos(eq("Fútbol 5"), eq(EstadoTorneo.INSCRIPCION_ABIERTA), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/v1/torneos/slug/{slug} es público y devuelve el detalle completo")
    void detallePublicoPorSlug() throws Exception {
        TorneoDetalle detalle = new TorneoDetalle(
                1L,
                "copa-verano",
                "Copa de Verano",
                "Torneo abierto de fútbol 5",
                "Fútbol 5",
                Formato.ELIMINACION_DIRECTA,
                Modalidad.PRESENCIAL,
                TipoParticipante.EQUIPO,
                "Sede Central",
                EstadoTorneo.INSCRIPCION_ABIERTA,
                16,
                4,
                12,
                5,
                new BigDecimal("5000.00"),
                "ARS",
                new BigDecimal("70.00"),
                new BigDecimal("20.00"),
                new BigDecimal("56000.00"),
                new BigDecimal("16000.00"),
                List.of(new PremioDetalle(1, new BigDecimal("70.00"), new BigDecimal("39200.00")),
                        new PremioDetalle(2, new BigDecimal("30.00"), new BigDecimal("16800.00"))),
                Instant.parse("2026-11-01T12:00:00Z"),
                Instant.parse("2026-11-05T10:00:00Z"),
                null,
                "Reglamento escrito",
                "https://pica.local/reglamento.pdf",
                "abc123hash",
                new OrganizadorResumen(2L, "Juan Perez", "juanp"),
                Instant.parse("2026-10-15T09:00:00Z"));

        when(torneoPublicoService.detallePublico("copa-verano")).thenReturn(detalle);

        mockMvc.perform(get("/api/v1/torneos/slug/copa-verano"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("copa-verano"))
                .andExpect(jsonPath("$.cupoDisponible").value(12))
                .andExpect(jsonPath("$.pozoEstimado").value(56000.00))
                .andExpect(jsonPath("$.reglamentoUrl").value("https://pica.local/reglamento.pdf"))
                .andExpect(jsonPath("$.premios[0].puesto").value(1))
                .andExpect(jsonPath("$.organizador.username").value("juanp"));
    }

    @Test
    @DisplayName("GET /api/v1/torneos/slug/{slug} responde 404 TORNEO_NO_ENCONTRADO si no existe o es borrador")
    void detalleNoEncontrado() throws Exception {
        when(torneoPublicoService.detallePublico("inexistente"))
                .thenThrow(new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "Torneo no encontrado"));

        mockMvc.perform(get("/api/v1/torneos/slug/inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("TORNEO_NO_ENCONTRADO"));
    }
}
