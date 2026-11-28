package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.ReprogramacionRequest;
import com.hydra.pica.plataforma_pica.torneo.service.ReprogramacionTorneoService;
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

/** Reprogramar un torneo (CR-01): el permiso, el cuerpo y el historial público; las reglas están en el servicio. */
@WebMvcTest(ReprogramacionTorneoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class ReprogramacionTorneoControllerTest {

    private static final Long ORGANIZADOR = 5L;
    private static final Instant INICIO = Instant.parse("2026-12-20T18:00:00Z");
    private static final Instant CIERRE = Instant.parse("2026-12-10T18:00:00Z");
    private static final Instant RECHAZO = Instant.parse("2026-12-05T18:00:00Z");
    private static final ReprogramacionDetalle DETALLE = new ReprogramacionDetalle(3L,
            Instant.parse("2026-12-06T18:00:00Z"), null, INICIO, null, CIERRE, RECHAZO, "Lluvia", true, 2,
            Instant.parse("2026-11-20T12:00:00Z"));
    private static final String CUERPO = """
            {"fechaInicio": "2026-12-20T18:00:00Z", "cierreInscripcion": "2026-12-10T18:00:00Z",
             "rechazoHasta": "2026-12-05T18:00:00Z", "motivo": "Lluvia"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReprogramacionTorneoService reprogramacionTorneoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Reprogramar sin TORNEO_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        mockMvc.perform(post("/api/v1/torneos/9/reprogramaciones").contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(reprogramacionTorneoService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Pasa las fechas y el motivo, y responde 201 con la reprogramación")
    void reprograma() throws Exception {
        ReprogramacionRequest request = new ReprogramacionRequest(INICIO, null, CIERRE, RECHAZO, "Lluvia");
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(reprogramacionTorneoService.reprogramar(ORGANIZADOR, 9L, request)).thenReturn(DETALLE);

        mockMvc.perform(post("/api/v1/torneos/9/reprogramaciones").contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.motivo").value("Lluvia"))
                .andExpect(jsonPath("$.plazoAbierto").value(true));

        verify(reprogramacionTorneoService).reprogramar(ORGANIZADOR, 9L, request);
    }

    @Test
    @DisplayName("El historial se ve sin iniciar sesión")
    void historialPublico() throws Exception {
        when(reprogramacionTorneoService.historial(9L)).thenReturn(List.of(DETALLE));

        mockMvc.perform(get("/api/v1/torneos/9/reprogramaciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].fechaInicioNueva").value("2026-12-20T18:00:00Z"))
                .andExpect(jsonPath("$[0].rechazos").value(2));
    }
}
