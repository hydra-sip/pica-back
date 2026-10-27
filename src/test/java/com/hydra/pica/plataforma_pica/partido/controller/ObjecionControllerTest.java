package com.hydra.pica.plataforma_pica.partido.controller;

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
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.dto.EvidenciaDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionComparada;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionResumen;
import com.hydra.pica.plataforma_pica.partido.dto.VersionResultado;
import com.hydra.pica.plataforma_pica.partido.service.ObjecionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ObjecionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class ObjecionControllerTest {

    private static final Long ARBITRO = 5L;
    private static final ObjecionResumen PENDIENTE = new ObjecionResumen(3L, 1, "Semifinal", "Los Galácticos",
            "Dep. Luján",
            new VersionResultado(Lado.LOCAL, "Los Galácticos", 3, 1, null, null,
                    Instant.parse("2026-10-26T18:00:00Z"), null),
            new VersionResultado(Lado.VISITANTE, "Dep. Luján", 1, 1, 4, 2,
                    Instant.parse("2026-10-26T18:05:00Z"), null),
            "Terminó empatado", Instant.parse("2026-10-26T18:05:00Z"), null, null);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private ObjecionService objecionService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/torneos/9/objeciones")).andExpect(status().isUnauthorized());
        verifyNoInteractions(objecionService);
    }

    @Test
    @WithMockUser
    @DisplayName("Las objeciones del torneo responden 200 con las dos versiones de cada una")
    void delTorneo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(objecionService.delTorneo(ARBITRO, 9L)).thenReturn(List.of(PENDIENTE));

        mockMvc.perform(get("/api/v1/torneos/9/objeciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].partidoId").value(3))
                .andExpect(jsonPath("$[0].cargada.lado").value("LOCAL"))
                .andExpect(jsonPath("$[0].cargada.puntajeLocal").value(3))
                .andExpect(jsonPath("$[0].objetada.equipo").value("Dep. Luján"))
                .andExpect(jsonPath("$[0].objetada.penalesLocal").value(4))
                .andExpect(jsonPath("$[0].motivo").value("Terminó empatado"))
                .andExpect(jsonPath("$[0].decision").isEmpty());
    }

    @Test
    @WithMockUser
    @DisplayName("Sin poder resolver objeciones responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(objecionService.delTorneo(ARBITRO, 9L))
                .thenThrow(new ProhibidoException(CodigoError.SIN_PERMISO, "no resuelve objeciones"));

        mockMvc.perform(get("/api/v1/torneos/9/objeciones"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @WithMockUser
    @DisplayName("El comparativo responde 200 con las dos versiones, su evidencia y el historial")
    void comparativo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        EvidenciaDetalle foto = new EvidenciaDetalle(21L, Lado.VISITANTE, "Dep. Luján", "image/jpeg", 2048, "ab12",
                "http://localhost/api/v1/partidos/3/evidencias/21/archivo", Instant.parse("2026-10-26T18:04:00Z"));
        when(objecionService.comparar(ARBITRO, 3L)).thenReturn(new ObjecionComparada(3L, 9L, EstadoPartido.OBJETADO,
                PENDIENTE.cargada(), new VersionResultado(Lado.VISITANTE, "Dep. Luján", 1, 1, 4, 2,
                        Instant.parse("2026-10-26T18:05:00Z"), List.of(foto)),
                "Terminó empatado", null, List.of()));

        mockMvc.perform(get("/api/v1/partidos/3/objecion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("OBJETADO"))
                .andExpect(jsonPath("$.objetada.evidencias[0].id").value(21))
                .andExpect(jsonPath("$.objetada.evidencias[0].equipo").value("Dep. Luján"))
                .andExpect(jsonPath("$.laudo").isEmpty())
                .andExpect(jsonPath("$.historial").isArray());
    }

    @Test
    @WithMockUser
    @DisplayName("Un partido sin objeción responde 404 OBJECION_NO_ENCONTRADA")
    void sinObjecion() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ARBITRO));
        when(objecionService.comparar(ARBITRO, 3L))
                .thenThrow(new NoEncontradoException(CodigoError.OBJECION_NO_ENCONTRADA, "nadie lo objetó"));

        mockMvc.perform(get("/api/v1/partidos/3/objecion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("OBJECION_NO_ENCONTRADA"));
    }
}
