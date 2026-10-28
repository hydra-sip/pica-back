package com.hydra.pica.plataforma_pica.partido.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.EstadoVentana;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.LadoCheckin;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.VentanaCheckin;
import com.hydra.pica.plataforma_pica.partido.dto.EvidenciaDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.LadoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.service.CheckinService;
import com.hydra.pica.plataforma_pica.partido.service.EvidenciaService;
import com.hydra.pica.plataforma_pica.partido.service.LaudoService;
import com.hydra.pica.plataforma_pica.partido.service.PartidoService;
import com.hydra.pica.plataforma_pica.partido.service.ProgramacionService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
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

/**
 * El partido, su resultado y su evidencia (PICA-241 a PICA-248): con sesión y sin permiso propio. Las
 * reglas están en los servicios.
 */
@WebMvcTest(PartidoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class PartidoControllerTest {

    private static final Long CAPITAN = 7L;
    private static final PartidoDetalle CARGADO = new PartidoDetalle(3L, 9L, "Copa", "copa", 1, "Semifinal", 1,
            EstadoPartido.RESULTADO_CARGADO, false, new LadoBracket(11L, "Los Galácticos", 3, null, true),
            new LadoBracket(12L, "Dep. Luján", 1, null, false), null, null, "Mario Gómez", Lado.LOCAL,
            Instant.parse("2026-10-26T18:00:00Z"), Instant.parse("2026-10-26T18:15:00Z"), Lado.LOCAL, null, false);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private PartidoService partidoService;
    @MockitoBean private ResultadoService resultadoService;
    @MockitoBean private EvidenciaService evidenciaService;
    @MockitoBean private LaudoService laudoService;
    @MockitoBean private ProgramacionService programacionService;
    @MockitoBean private CheckinService checkinService;
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin sesión responde 401")
    void sinSesion() throws Exception {
        mockMvc.perform(get("/api/v1/partidos/3")).andExpect(status().isUnauthorized());
        verifyNoInteractions(partidoService);
    }

    @Test
    @WithMockUser
    @DisplayName("Cargar el resultado responde 200 con el partido pendiente de confirmación")
    void carga() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(resultadoService.cargar(CAPITAN, 3L, new ResultadoRequest(3, 1, null, null))).thenReturn(CARGADO);

        mockMvc.perform(post("/api/v1/partidos/3/resultado").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puntajeLocal\":3,\"puntajeVisitante\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RESULTADO_CARGADO"))
                .andExpect(jsonPath("$.cargadoPor").value("LOCAL"))
                .andExpect(jsonPath("$.objecionVenceEn").value("2026-10-26T18:15:00Z"));
    }

    @Test
    @WithMockUser
    @DisplayName("La ventana de check-in responde 200 con el estado de cada lado")
    void verCheckin() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(checkinService.ver(CAPITAN, 3L)).thenReturn(new CheckinPartido(
                new VentanaCheckin(Instant.parse("2026-11-03T17:30:00Z"), Instant.parse("2026-11-03T17:50:00Z")),
                EstadoVentana.ABIERTA, new LadoCheckin("Los Galácticos", Instant.parse("2026-11-03T17:35:00Z")),
                null, Lado.LOCAL));

        mockMvc.perform(get("/api/v1/partidos/3/checkin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventana.abreEn").value("2026-11-03T17:30:00Z"))
                .andExpect(jsonPath("$.ventana.cierraEn").value("2026-11-03T17:50:00Z"))
                .andExpect(jsonPath("$.estadoVentana").value("ABIERTA"))
                .andExpect(jsonPath("$.local.nombre").value("Los Galácticos"))
                .andExpect(jsonPath("$.local.checkinEn").value("2026-11-03T17:35:00Z"))
                .andExpect(jsonPath("$.visitante").isEmpty())
                .andExpect(jsonPath("$.miLado").value("LOCAL"));
    }

    @Test
    @WithMockUser
    @DisplayName("Un check-in fuera de la ventana responde 409 con cuándo abre y cuándo cierra")
    void checkinFueraDeVentana() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(checkinService.hacer(CAPITAN, 3L)).thenThrow(
                new ConflictoException(CodigoError.CHECKIN_FUERA_DE_VENTANA, "Fuera de la ventana")
                        .con("abreEn", Instant.parse("2026-11-03T17:30:00Z"))
                        .con("cierraEn", Instant.parse("2026-11-03T17:50:00Z")));

        mockMvc.perform(post("/api/v1/partidos/3/checkin"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CHECKIN_FUERA_DE_VENTANA"))
                .andExpect(jsonPath("$.abreEn").value("2026-11-03T17:30:00Z"))
                .andExpect(jsonPath("$.cierraEn").value("2026-11-03T17:50:00Z"));
    }

    @Test
    @WithMockUser
    @DisplayName("Un puntaje que falta o es negativo responde 400 sin llegar al servicio")
    void puntajeInvalido() throws Exception {
        mockMvc.perform(post("/api/v1/partidos/3/resultado").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puntajeLocal\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'puntajeVisitante')].codigo").value("REQUERIDO"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'puntajeLocal')].codigo").value("VALOR_INVALIDO"));
        verifyNoInteractions(resultadoService);
    }

    @Test
    @WithMockUser
    @DisplayName("Programar el partido pasa la fecha y el usuario de la sesión")
    void programa() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(programacionService.programar(CAPITAN, 3L, Instant.parse("2026-11-03T18:00:00Z"))).thenReturn(CARGADO);

        mockMvc.perform(put("/api/v1/partidos/3/programacion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaProgramada\":\"2026-11-03T18:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3));
    }

    @Test
    @WithMockUser
    @DisplayName("Sin ser capitán responde 403 NO_ES_CAPITAN")
    void noEsCapitan() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(resultadoService.cargar(eq(CAPITAN), eq(3L), any()))
                .thenThrow(new ProhibidoException(CodigoError.NO_ES_CAPITAN, "no es capitán"));

        mockMvc.perform(post("/api/v1/partidos/3/resultado").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puntajeLocal\":3,\"puntajeVisitante\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("NO_ES_CAPITAN"));
    }

    @Test
    @WithMockUser
    @DisplayName("Aceptar el propio resultado responde 409 RESULTADO_PROPIO")
    void aceptarPropio() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(resultadoService.aceptar(CAPITAN, 3L))
                .thenThrow(new ConflictoException(CodigoError.RESULTADO_PROPIO, "lo acepta el rival"));

        mockMvc.perform(post("/api/v1/partidos/3/resultado/aceptar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RESULTADO_PROPIO"));
    }

    @Test
    @WithMockUser
    @DisplayName("Objetar sin motivo responde 400 sin llegar al servicio")
    void objecionSinMotivo() throws Exception {
        mockMvc.perform(post("/api/v1/partidos/3/objecion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"  \",\"puntajeLocal\":1,\"puntajeVisitante\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'motivo')].codigo").value("REQUERIDO"));
        verifyNoInteractions(resultadoService);
    }

    @Test
    @WithMockUser
    @DisplayName("Objetar fuera de plazo responde 409 PLAZO_OBJECION_VENCIDO")
    void objecionVencida() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(resultadoService.objetar(eq(CAPITAN), eq(3L), any()))
                .thenThrow(new ConflictoException(CodigoError.PLAZO_OBJECION_VENCIDO, "venció"));

        mockMvc.perform(post("/api/v1/partidos/3/objecion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\":\"Fue 1-1\",\"puntajeLocal\":1,\"puntajeVisitante\":1,"
                                + "\"penalesLocal\":4,\"penalesVisitante\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("PLAZO_OBJECION_VENCIDO"));
    }

    @Test
    @WithMockUser
    @DisplayName("Un laudo sin justificación ni evidencia responde 400 sin llegar al servicio")
    void laudoIncompleto() throws Exception {
        mockMvc.perform(post("/api/v1/partidos/3/laudo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"puntajeLocal\":0,\"puntajeVisitante\":1,\"justificacion\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'justificacion')].codigo").value("REQUERIDO"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'evidenciaId')].codigo").value("REQUERIDO"));
        verifyNoInteractions(laudoService);
    }

    @Test
    @WithMockUser
    @DisplayName("Mi próximo partido responde 204 si no hay ninguno")
    void sinProximo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        when(partidoService.miProximo(CAPITAN, 9L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/torneos/9/mi-proximo-partido")).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser
    @DisplayName("Subir evidencia responde 201 con Location al archivo")
    void subeEvidencia() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        byte[] foto = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
        String url = "http://localhost/api/v1/partidos/3/evidencias/5/archivo";
        when(evidenciaService.subir(CAPITAN, 3L, foto, "http://localhost/api/v1/partidos/3/evidencias"))
                .thenReturn(new EvidenciaDetalle(5L, Lado.LOCAL, "Los Galácticos", "image/jpeg", 4, "ab".repeat(32),
                        url, Instant.parse("2026-10-26T18:01:00Z")));

        mockMvc.perform(multipart("/api/v1/partidos/3/evidencias")
                        .file(new MockMultipartFile("archivo", "planilla.jpg", "image/jpeg", foto)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", url))
                .andExpect(jsonPath("$.lado").value("LOCAL"));
    }

    @Test
    @WithMockUser
    @DisplayName("El archivo de evidencia se sirve con su tipo")
    void archivo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(CAPITAN));
        byte[] foto = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0};
        when(evidenciaService.archivo(CAPITAN, 3L, 5L)).thenReturn(new EvidenciaService.Archivo(foto, "image/jpeg"));

        mockMvc.perform(get("/api/v1/partidos/3/evidencias/5/archivo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(foto));
    }
}
