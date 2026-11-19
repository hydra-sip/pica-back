package com.hydra.pica.plataforma_pica.reputacion.controller;

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
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilOrganizador;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilOrganizador.MovimientoIndice;
import com.hydra.pica.plataforma_pica.reputacion.service.PerfilOrganizadorService;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.OrganizadorResumen;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PerfilOrganizadorController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class PerfilOrganizadorControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PerfilOrganizadorService perfilOrganizadorService;

    @Test
    @DisplayName("GET /api/v1/usuarios/{id}/perfil-organizador es público y expone solo datos públicos")
    void publicoSinSesion() throws Exception {
        Instant cuando = Instant.parse("2026-11-18T15:00:00Z");
        TorneoResumen proximo = new TorneoResumen(
                10L, "copa-otono", "Copa Otoño", "Abierta", "Fútbol 5",
                Formato.ELIMINACION_DIRECTA, Modalidad.PRESENCIAL, TipoParticipante.EQUIPO,
                "Sede Norte", EstadoTorneo.INSCRIPCION_ABIERTA, 16, 10,
                new BigDecimal("4000.00"), "ARS", new BigDecimal("400.00"), new BigDecimal("50000.00"),
                Instant.parse("2026-11-25T12:00:00Z"), Instant.parse("2026-12-01T10:00:00Z"), null,
                new OrganizadorResumen(5L, "Ana Gómez", "anagomez"));
        PerfilOrganizador perfil = new PerfilOrganizador(
                5L, "anagomez", "Ana Gómez", null,
                4, 2, 1, 85, cuando,
                List.of(new MovimientoIndice(7L, "Copa Invierno", false, -15, 85, cuando)),
                List.of(proximo));

        when(perfilOrganizadorService.ver(5L)).thenReturn(perfil);

        mockMvc.perform(get("/api/v1/usuarios/5/perfil-organizador"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.username").value("anagomez"))
                .andExpect(jsonPath("$.nombreCompleto").value("Ana Gómez"))
                .andExpect(jsonPath("$.torneosOrganizados").value(4))
                .andExpect(jsonPath("$.torneosFinalizados").value(2))
                .andExpect(jsonPath("$.torneosCancelados").value(1))
                .andExpect(jsonPath("$.indice").value(85))
                .andExpect(jsonPath("$.historialIndice[0].torneoId").value(7))
                .andExpect(jsonPath("$.historialIndice[0].delta").value(-15))
                .andExpect(jsonPath("$.torneosProximos[0].slug").value("copa-otono"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.nroDoc").doesNotExist())
                .andExpect(jsonPath("$.domicilioPostal").doesNotExist())
                .andExpect(jsonPath("$.telefono").doesNotExist());
    }

    @Test
    @DisplayName("Responde 404 USUARIO_NO_ENCONTRADO si el organizador no existe")
    void noExiste() throws Exception {
        when(perfilOrganizadorService.ver(999L))
                .thenThrow(new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario 999"));

        mockMvc.perform(get("/api/v1/usuarios/999/perfil-organizador"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }
}
