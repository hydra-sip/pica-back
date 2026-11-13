package com.hydra.pica.plataforma_pica.rifa.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle.ReclamanteResumen;
import com.hydra.pica.plataforma_pica.rifa.dto.ReclamoAdminDetalle.RifaReclamoResumen;
import com.hydra.pica.plataforma_pica.rifa.dto.ResolverReclamoRequest;
import com.hydra.pica.plataforma_pica.rifa.service.LiberacionReclamoService;
import com.hydra.pica.plataforma_pica.rifa.service.ReclamoAdminService;
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

@WebMvcTest(AdminReclamoController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class AdminReclamoControllerTest {

    private static final Long SOPORTE_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReclamoAdminService reclamoAdminService;

    @MockitoBean
    private LiberacionReclamoService liberacionReclamoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin autenticación los endpoints de admin/reclamos responden 401")
    void sinAutenticacionResponde401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reclamos")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/reclamos/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/reclamos/1/resolucion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"Todo ok\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/reclamos/1/liberar"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reclamoAdminService, liberacionReclamoService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("Sin permiso RECLAMO_RESOLVER responden 403 SIN_PERMISO")
    void sinPermisoResponde403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reclamos"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        mockMvc.perform(get("/api/v1/admin/reclamos/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        mockMvc.perform(post("/api/v1/admin/reclamos/1/resolucion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"Todo ok\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        mockMvc.perform(post("/api/v1/admin/reclamos/1/liberar"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));

        verifyNoInteractions(reclamoAdminService, liberacionReclamoService);
    }

    @Test
    @WithMockUser(authorities = "RECLAMO_RESOLVER")
    @DisplayName("GET /api/v1/admin/reclamos lista paginado con filtro de estado")
    void listarReclamosConFiltroYPaginacion() throws Exception {
        ReclamoAdminDetalle item = detalle(1L, EstadoReclamo.PENDIENTE, EstadoRifa.EN_REVISION, null, null, null);
        when(reclamoAdminService.listar(eq(EstadoReclamo.PENDIENTE), eq(PageRequest.of(1, 10))))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(1, 10), 11));

        mockMvc.perform(get("/api/v1/admin/reclamos")
                        .param("estado", "PENDIENTE")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.content[0].rifa.id").value(10))
                .andExpect(jsonPath("$.content[0].rifa.titulo").value("Rifa Apertura"))
                .andExpect(jsonPath("$.content[0].reclamante.username").value("jperez"))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(11));
    }

    @Test
    @WithMockUser(authorities = "RECLAMO_RESOLVER")
    @DisplayName("GET /api/v1/admin/reclamos/{id} responde 200 o 404 RECLAMO_NO_ENCONTRADO")
    void detalleReclamo() throws Exception {
        when(reclamoAdminService.detalle(1L))
                .thenReturn(detalle(1L, EstadoReclamo.PENDIENTE, EstadoRifa.EN_REVISION, null, null, null));
        when(reclamoAdminService.detalle(999L))
                .thenThrow(new NoEncontradoException(CodigoError.RECLAMO_NO_ENCONTRADO, "No existe el reclamo 999"));

        mockMvc.perform(get("/api/v1/admin/reclamos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.rifa.torneoNombre").value("Copa UNLu"));

        mockMvc.perform(get("/api/v1/admin/reclamos/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(authorities = "RECLAMO_RESOLVER")
    @DisplayName("POST /api/v1/admin/reclamos/{id}/resolucion válido responde 200")
    void resolverExitoso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE_ID));
        ReclamoAdminDetalle resuelto = detalle(
                1L, EstadoReclamo.RECHAZADO, EstadoRifa.SORTEADA,
                "Semilla verificada correctamente", Instant.parse("2026-11-11T15:00:00Z"), "soporte");
        when(reclamoAdminService.resolver(eq(SOPORTE_ID), eq(1L), any(ResolverReclamoRequest.class)))
                .thenReturn(resuelto);

        mockMvc.perform(post("/api/v1/admin/reclamos/1/resolucion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"Semilla verificada correctamente\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADO"))
                .andExpect(jsonPath("$.resolucion").value("Semilla verificada correctamente"))
                .andExpect(jsonPath("$.resueltoPor").value("soporte"))
                .andExpect(jsonPath("$.rifa.estado").value("SORTEADA"));

        verify(reclamoAdminService).resolver(SOPORTE_ID, 1L,
                new ResolverReclamoRequest(DecisionReclamo.SIN_FRAUDE, "Semilla verificada correctamente"));
    }

    @Test
    @WithMockUser(authorities = "RECLAMO_RESOLVER")
    @DisplayName("POST /api/v1/admin/reclamos/{id}/resolucion sin resolución o sin decisión responde 400 VALIDACION")
    void resolverSinDatosObligatoriosResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/reclamos/1/resolucion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SIN_FRAUDE\",\"resolucion\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("resolucion"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));

        mockMvc.perform(post("/api/v1/admin/reclamos/1/resolucion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolucion\":\"Falta la decisión\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("decision"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));

        verifyNoInteractions(reclamoAdminService);
    }

    @Test
    @WithMockUser(authorities = "RECLAMO_RESOLVER")
    @DisplayName("POST /api/v1/admin/reclamos/{id}/resolucion sobre reclamo ya resuelto responde 409 RECLAMO_YA_RESUELTO")
    void resolverYaResueltoResponde409() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE_ID));
        when(reclamoAdminService.resolver(eq(SOPORTE_ID), eq(1L), any()))
                .thenThrow(new ConflictoException(CodigoError.RECLAMO_YA_RESUELTO, "Ya fue resuelto"));

        mockMvc.perform(post("/api/v1/admin/reclamos/1/resolucion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FRAUDE_CONFIRMADO\",\"resolucion\":\"Reapertura\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_YA_RESUELTO"));
    }

    @Test
    @WithMockUser(authorities = "RECLAMO_RESOLVER")
    @DisplayName("POST /api/v1/admin/reclamos/{id}/liberar exitoso responde 200 con datos de liberación")
    void liberarFondosExitoso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE_ID));
        ReclamoAdminDetalle liberado = new ReclamoAdminDetalle(
                1L,
                "Inconsistencia en el resultado",
                EstadoReclamo.RECHAZADO,
                "Semilla validada",
                Instant.parse("2026-11-11T15:00:00Z"),
                "soporte",
                Instant.parse("2026-11-10T15:00:00Z"),
                new RifaReclamoResumen(10L, "Rifa Apertura", EstadoRifa.LIQUIDADA, "Copa UNLu",
                        Instant.parse("2026-11-10T12:00:00Z"), Instant.parse("2026-11-10T12:00:05Z")),
                new ReclamanteResumen(42L, "jperez", "juan@example.com"),
                Instant.parse("2026-11-12T18:00:00Z"),
                "soporte",
                new BigDecimal("1100.00"));
        when(liberacionReclamoService.liberarFondos(SOPORTE_ID, 1L)).thenReturn(liberado);

        mockMvc.perform(post("/api/v1/admin/reclamos/1/liberar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rifa.estado").value("LIQUIDADA"))
                .andExpect(jsonPath("$.fondosLiberadosPor").value("soporte"))
                .andExpect(jsonPath("$.fondosLiberadosMonto").value(1100.00))
                .andExpect(jsonPath("$.fondosLiberadosEn").value("2026-11-12T18:00:00Z"));

        verify(liberacionReclamoService).liberarFondos(SOPORTE_ID, 1L);
    }

    @Test
    @WithMockUser(authorities = "RECLAMO_RESOLVER")
    @DisplayName("POST /api/v1/admin/reclamos/{id}/liberar mapea 404 y conflictos 409")
    void liberarFondosErroresDeNegocio() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(SOPORTE_ID));
        when(liberacionReclamoService.liberarFondos(SOPORTE_ID, 999L))
                .thenThrow(new NoEncontradoException(CodigoError.RECLAMO_NO_ENCONTRADO, "No existe"));
        when(liberacionReclamoService.liberarFondos(SOPORTE_ID, 2L))
                .thenThrow(new ConflictoException(CodigoError.RECLAMO_NO_RESUELTO, "Pendiente"));
        when(liberacionReclamoService.liberarFondos(SOPORTE_ID, 3L))
                .thenThrow(new ConflictoException(CodigoError.CUSTODIA_LIBERADA, "Ya liberado"));

        mockMvc.perform(post("/api/v1/admin/reclamos/999/liberar"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_NO_ENCONTRADO"));

        mockMvc.perform(post("/api/v1/admin/reclamos/2/liberar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RECLAMO_NO_RESUELTO"));

        mockMvc.perform(post("/api/v1/admin/reclamos/3/liberar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CUSTODIA_LIBERADA"));
    }

    private static ReclamoAdminDetalle detalle(Long id, EstadoReclamo estado, EstadoRifa estadoRifa,
                                               String resolucion, Instant resueltoEn, String resueltoPor) {
        return new ReclamoAdminDetalle(
                id,
                "Inconsistencia en el resultado",
                estado,
                resolucion,
                resueltoEn,
                resueltoPor,
                Instant.parse("2026-11-10T15:00:00Z"),
                new RifaReclamoResumen(10L, "Rifa Apertura", estadoRifa, "Copa UNLu",
                        Instant.parse("2026-11-10T12:00:00Z"), Instant.parse("2026-11-10T12:00:05Z")),
                new ReclamanteResumen(42L, "jperez", "juan@example.com"),
                null,
                null,
                null);
    }
}

