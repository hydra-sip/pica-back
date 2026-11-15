package com.hydra.pica.plataforma_pica.partido.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.partido.domain.TablaGrupo;
import com.hydra.pica.plataforma_pica.partido.dto.GruposTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.SorteoGruposDetalle;
import com.hydra.pica.plataforma_pica.partido.service.SorteoGruposService;
import com.hydra.pica.plataforma_pica.partido.service.TablaGruposService;
import com.hydra.pica.plataforma_pica.torneo.domain.ReglasGrupos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** El sorteo de grupos (CR-02-3) es público, para el portal de verificación. */
@WebMvcTest(GruposController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class GruposControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SorteoGruposService sorteoGruposService;

    @MockitoBean
    private TablaGruposService tablaGruposService;

    @Test
    @DisplayName("GET /grupos responde sin sesión con las tablas y el criterio de cada puesto")
    void gruposPublicos() throws Exception {
        when(tablaGruposService.ver(9L)).thenReturn(new GruposTorneo(9L, ReglasGrupos.POR_DEFECTO,
                "puntos,entre-si,ganados,partido", List.of(new GruposTorneo.Grupo("A", true,
                List.of(new GruposTorneo.Fila(4L, "Ana", 1, 3, 2, 0, 1, 5, 2, 3, 6, TablaGrupo.Criterio.ENTRE_SI,
                        true)),
                List.of(), List.of(), List.of()))));

        mockMvc.perform(get("/api/v1/torneos/9/grupos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reglas.clasificanPorGrupo").value(2))
                .andExpect(jsonPath("$.grupos[0].tabla[0].criterio").value("ENTRE_SI"))
                .andExpect(jsonPath("$.grupos[0].tabla[0].clasifica").value(true));
    }

    @Test
    @DisplayName("GET /sorteo-grupos responde sin sesión con el compromiso, la semilla y los grupos")
    void sorteoPublico() throws Exception {
        when(sorteoGruposService.ver(9L)).thenReturn(new SorteoGruposDetalle(9L, "Copa", 1, "ab".repeat(32),
                Instant.parse("2026-11-20T12:00:00Z"), List.of(3L, 4L), 777L, "cd".repeat(32), "ef".repeat(32),
                "firma", "https://api.drand.sh/x/public/777", 1,
                List.of(new SorteoGruposDetalle.GrupoSorteado("A", List.of(
                        new SorteoGruposDetalle.Integrante(4L, "Ana"),
                        new SorteoGruposDetalle.Integrante(3L, "Beto"))))));

        mockMvc.perform(get("/api/v1/torneos/9/sorteo-grupos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semillaRonda").value(777))
                .andExpect(jsonPath("$.grupos[0].letra").value("A"))
                .andExpect(jsonPath("$.grupos[0].integrantes[0].inscripcionId").value(4));
    }
}
