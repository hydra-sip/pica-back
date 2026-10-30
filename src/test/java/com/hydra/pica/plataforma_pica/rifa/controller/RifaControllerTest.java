package com.hydra.pica.plataforma_pica.rifa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.FaltantePublicacion;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaResumen;
import com.hydra.pica.plataforma_pica.rifa.service.CierreVentaService;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.SorteoRifaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** /api/v1/rifas (PICA-226): el id del organizador sale de la sesión y las reglas están en RifaService. */
@WebMvcTest(RifaController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class RifaControllerTest {

    private static final Long ORGANIZADOR = 5L;
    private static final Instant CREADA = Instant.parse("2026-10-22T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RifaService rifaService;

    @MockitoBean
    private CierreVentaService cierreVentaService;

    @MockitoBean
    private SorteoRifaService sorteoRifaService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin autenticación responde 401")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/rifas/mis-rifas"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rifaService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("Sin RIFA_GESTIONAR (no es organizador) responde 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/rifas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titulo": "Rifa", "numeroDesde": 1, "numeroHasta": 100, "precioNumero": 500}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(get("/api/v1/rifas/mis-rifas"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rifaService);
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("POST /api/v1/rifas crea con el usuario de la sesión y responde 201 con Location")
    void crea() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(rifaService.crear(eq(ORGANIZADOR), any(RifaRequest.class))).thenReturn(detalle());

        mockMvc.perform(post("/api/v1/rifas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titulo": "Rifa del club", "numeroDesde": 1, "numeroHasta": 100,
                                 "precioNumero": 500, "torneoId": 3, "pctDestinadoTorneo": 35}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/rifas/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.estado").value("BORRADOR"))
                .andExpect(jsonPath("$.torneoNombre").value("Copa del club"));

        ArgumentCaptor<RifaRequest> captor = ArgumentCaptor.forClass(RifaRequest.class);
        verify(rifaService).crear(eq(ORGANIZADOR), captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new RifaRequest("Rifa del club", null, 1, 100, new BigDecimal("500"), null, null, null, 3L,
                        new BigDecimal("35")));
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("POST /api/v1/rifas sin título ni precio responde 400 VALIDACION sin llamar al servicio")
    void faltanCampos() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/rifas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titulo": " ", "numeroDesde": 1, "numeroHasta": 100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'titulo')].codigo").value("REQUERIDO"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'precioNumero')].codigo").value("REQUERIDO"));
        verify(rifaService, never()).crear(any(), any());
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("POST /api/v1/rifas con lo destinado al torneo con decimales o de 100 responde 400")
    void destinadoAlTorneoInvalido() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        for (String pct : List.of("12.5", "100")) {
            mockMvc.perform(post("/api/v1/rifas")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"titulo": "Rifa", "numeroDesde": 1, "numeroHasta": 100, "precioNumero": 500,
                                     "torneoId": 3, "pctDestinadoTorneo": %s}
                                    """.formatted(pct)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errores[0].campo").value("pctDestinadoTorneo"));
        }
        verify(rifaService, never()).crear(any(), any());
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("PUT /api/v1/rifas/{id}/premios pasa la lista en orden y devuelve la rifa con los premios")
    void reemplazaPremios() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(rifaService.reemplazarPremios(eq(ORGANIZADOR), eq(7L), any())).thenReturn(detalle());

        mockMvc.perform(put("/api/v1/rifas/7/premios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"premios": [{"pct": 100, "descripcion": "Una bici"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premios[0].puesto").value(1))
                .andExpect(jsonPath("$.premios[0].descripcion").value("Una bici"));

        verify(rifaService).reemplazarPremios(ORGANIZADOR, 7L,
                List.of(new PremioRifaRequest(new BigDecimal("100"), "Una bici")));
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("PUT /api/v1/rifas/{id}/premios con un premio de 0 %, con decimales o sin lista responde 400")
    void premiosInvalidos() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(put("/api/v1/rifas/7/premios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"premios": [{"pct": 0}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        mockMvc.perform(put("/api/v1/rifas/7/premios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"premios": [{"pct": 10.5}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("premios[0].pct"));
        mockMvc.perform(put("/api/v1/rifas/7/premios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("premios"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));
        verify(rifaService, never()).reemplazarPremios(any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("POST /api/v1/rifas/{id}/publicar con términos y lotería aceptados publica la rifa")
    void publica() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(rifaService.publicar(ORGANIZADOR, 7L)).thenReturn(detalle());

        mockMvc.perform(post("/api/v1/rifas/7/publicar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"aceptaTerminos": true, "declaraAutorizacionLoteria": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
        verify(rifaService).publicar(ORGANIZADOR, 7L);
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("POST /api/v1/rifas/{id}/publicar sin aceptar los términos o sin declarar la lotería responde 400")
    void publicarSinAceptar() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(post("/api/v1/rifas/7/publicar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"aceptaTerminos": false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'aceptaTerminos')].codigo").value("REQUERIDO"))
                .andExpect(jsonPath("$.errores[?(@.campo == 'declaraAutorizacionLoteria')].codigo").value("REQUERIDO"));
        verify(rifaService, never()).publicar(any(), any());
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("POST /api/v1/rifas/{id}/publicar de una rifa incompleta responde 409 RIFA_INCOMPLETA con los faltantes")
    void publicarIncompleta() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(rifaService.publicar(ORGANIZADOR, 7L)).thenThrow(
                new ConflictoException(CodigoError.RIFA_INCOMPLETA, "Le falta algo")
                        .con("faltantes", List.of(FaltantePublicacion.FECHA_SORTEO, FaltantePublicacion.PREMIOS_NO_SUMAN_100))
                        .con("sumaPremios", 80));

        mockMvc.perform(post("/api/v1/rifas/7/publicar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"aceptaTerminos": true, "declaraAutorizacionLoteria": true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RIFA_INCOMPLETA"))
                .andExpect(jsonPath("$.faltantes[0]").value("FECHA_SORTEO"))
                .andExpect(jsonPath("$.faltantes[1]").value("PREMIOS_NO_SUMAN_100"))
                .andExpect(jsonPath("$.sumaPremios").value(80));
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("POST /api/v1/rifas/{id}/volver-a-borrador con números vendidos responde 409 RIFA_CON_VENTAS")
    void volverABorradorConVentas() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(rifaService.volverABorrador(ORGANIZADOR, 7L)).thenThrow(
                new ConflictoException(CodigoError.RIFA_CON_VENTAS, "Tiene ventas").con("numerosVendidos", 3L));

        mockMvc.perform(post("/api/v1/rifas/7/volver-a-borrador"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("RIFA_CON_VENTAS"))
                .andExpect(jsonPath("$.numerosVendidos").value(3));
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("DELETE /api/v1/rifas/{id} da de baja la rifa del usuario de la sesión y responde 204")
    void elimina() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));

        mockMvc.perform(delete("/api/v1/rifas/7"))
                .andExpect(status().isNoContent());
        verify(rifaService).eliminar(ORGANIZADOR, 7L);
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("PUT /api/v1/rifas/{id} de una rifa ajena responde 404 RIFA_NO_ENCONTRADA")
    void modificarAjena() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(rifaService.modificar(eq(ORGANIZADOR), eq(9L), any(RifaRequest.class)))
                .thenThrow(new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa 9"));

        mockMvc.perform(put("/api/v1/rifas/9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titulo": "Rifa", "numeroDesde": 1, "numeroHasta": 100, "precioNumero": 500}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("RIFA_NO_ENCONTRADA"));
    }

    @Test
    @WithMockUser(authorities = "RIFA_GESTIONAR")
    @DisplayName("GET /api/v1/rifas/mis-rifas devuelve la página del usuario de la sesión")
    void misRifas() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ORGANIZADOR));
        when(rifaService.listarMias(eq(ORGANIZADOR), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new RifaResumen(7L, "Rifa del club", null, null, EstadoRifa.BORRADOR, 1, 100, new BigDecimal("500.00"), "ARS",
                        null, null, null, CREADA)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/rifas/mis-rifas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].titulo").value("Rifa del club"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    private static RifaDetalle detalle() {
        return new RifaDetalle(7L, "Rifa del club", null, 3L, "Copa del club", 0, EstadoRifa.BORRADOR, 1, 100,
                new BigDecimal("500.00"),
                "ARS", null, null, null, null, null, List.of(new PremioRifaDetalle(1, 100, "Una bici", null)), 0,
                new RepartoRifa(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null, null, null, null, CREADA, CREADA);
    }
}
