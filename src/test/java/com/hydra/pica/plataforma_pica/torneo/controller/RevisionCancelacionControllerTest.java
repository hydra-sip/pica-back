package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.dto.Comprobante;
import com.hydra.pica.plataforma_pica.torneo.dto.ResolucionCancelacion;
import com.hydra.pica.plataforma_pica.torneo.service.RevisionCancelacionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** La revisión de soporte de las cancelaciones (HU-605, PICA-348): lo HTTP; las reglas están en el servicio. */
@WebMvcTest(RevisionCancelacionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class RevisionCancelacionControllerTest {

    private static final Long SOPORTE = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RevisionCancelacionService revisionCancelacionService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Sin CANCELACION_REVISAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(post("/api/v1/admin/cancelaciones/9/rechazar")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"No\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(revisionCancelacionService);
    }

    @Test
    @WithMockUser(authorities = "CANCELACION_REVISAR")
    @DisplayName("Rechazar pasa el motivo y responde con la resolución")
    void rechazar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE));
        when(revisionCancelacionService.rechazar(SOPORTE, 9L, "No son del torneo")).thenReturn(
                new ResolucionCancelacion(9L, "RECHAZADA", new BigDecimal("3300.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO));

        mockMvc.perform(post("/api/v1/admin/cancelaciones/9/rechazar")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"No son del torneo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.reintegrado").value(3300.00));

        verify(revisionCancelacionService).rechazar(SOPORTE, 9L, "No son del torneo");
    }

    @Test
    @WithMockUser(authorities = "CANCELACION_REVISAR")
    @DisplayName("Aprobar no lleva body y responde con lo que cobró el organizador")
    void aprobar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE));
        when(revisionCancelacionService.aprobar(SOPORTE, 9L)).thenReturn(new ResolucionCancelacion(9L, "APROBADA",
                new BigDecimal("2849.50"), new BigDecimal("450.50"), new BigDecimal("450.50")));

        mockMvc.perform(post("/api/v1/admin/cancelaciones/9/aprobar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.alOrganizador").value(450.50));
    }

    @Test
    @WithMockUser(authorities = "CANCELACION_REVISAR")
    @DisplayName("La cola pasa la página pedida y responde paginada")
    void cola() throws Exception {
        when(revisionCancelacionService.cola(PageRequest.of(2, 5))).thenReturn(new PageImpl<>(List.of(),
                PageRequest.of(2, 5), 11));

        mockMvc.perform(get("/api/v1/admin/cancelaciones").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.totalElements").value(11));
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("La cola también pide CANCELACION_REVISAR")
    void colaSinPermiso() throws Exception {
        mockMvc.perform(get("/api/v1/admin/cancelaciones")).andExpect(status().isForbidden());
        verifyNoInteractions(revisionCancelacionService);
    }

    @Test
    @WithMockUser(authorities = "CANCELACION_REVISAR")
    @DisplayName("El comprobante va con su tipo y para abrirlo en el navegador")
    void comprobante() throws Exception {
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
        when(revisionCancelacionService.comprobante(9L, 4L)).thenReturn(new Comprobante(jpg, "image/jpeg"));

        mockMvc.perform(get("/api/v1/admin/cancelaciones/9/gastos/4/comprobante"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Content-Disposition", startsWith("inline")))
                .andExpect(content().bytes(jpg));
    }
}
