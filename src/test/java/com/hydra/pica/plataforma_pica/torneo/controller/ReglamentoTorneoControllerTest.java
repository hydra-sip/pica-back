package com.hydra.pica.plataforma_pica.torneo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.torneo.service.ReglamentoTorneoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** El PDF del reglamento (PICA-200): subir pide permiso, verlo no; las reglas están en el servicio. */
@WebMvcTest(ReglamentoTorneoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class ReglamentoTorneoControllerTest {

    private static final Long ORGANIZADOR = 5L;
    private static final byte[] PDF = "%PDF-1.7\nprueba".getBytes(StandardCharsets.US_ASCII);
    private static final MockMultipartFile ARCHIVO =
            new MockMultipartFile("archivo", "reglamento.pdf", MediaType.APPLICATION_PDF_VALUE, PDF);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReglamentoTorneoService reglamentoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Subir sin TORNEO_GESTIONAR responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        mockMvc.perform(multipart("/api/v1/torneos/9/reglamento").file(ARCHIVO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(reglamentoService);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Subir pasa el archivo y la URL absoluta del GET")
    void sube() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(multipart("/api/v1/torneos/9/reglamento").file(ARCHIVO))
                .andExpect(status().isOk());

        verify(reglamentoService).subir(ORGANIZADOR, 9L, PDF, "http://localhost/api/v1/torneos/9/reglamento");
    }

    @Test
    @WithMockUser(authorities = "TORNEO_GESTIONAR")
    @DisplayName("Sin la parte archivo responde 400 REQUERIDO")
    void sinArchivo() throws Exception {
        mockMvc.perform(multipart("/api/v1/torneos/9/reglamento"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("archivo"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));
        verifyNoInteractions(reglamentoService);
    }

    @Test
    @DisplayName("Verlo no pide sesión y devuelve el PDF para mostrar en el navegador")
    void verSinSesion() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.empty());
        when(reglamentoService.pdf(Optional.empty(), 9L)).thenReturn(PDF);

        mockMvc.perform(get("/api/v1/torneos/9/reglamento"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"reglamento-9.pdf\""))
                .andExpect(content().bytes(PDF));
    }

    @Test
    @DisplayName("Quitar sin sesión responde 401")
    void quitarSinSesion() throws Exception {
        mockMvc.perform(delete("/api/v1/torneos/9/reglamento"))
                .andExpect(status().isUnauthorized());
        verify(reglamentoService, never()).quitar(any(), eq(9L));
    }
}
