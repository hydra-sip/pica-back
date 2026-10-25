package com.hydra.pica.plataforma_pica.inscripcion.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
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

import com.hydra.pica.plataforma_pica.billetera.service.CobroRechazadoException;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ResumenPago;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Inscripción individual (PICA-208): el usuario sale de la sesión y las reglas están en InscripcionService. */
@WebMvcTest(InscripcionController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class InscripcionControllerTest {

    private static final Long PARTICIPANTE = 5L;
    private static final Instant CREADA = Instant.parse("2026-10-24T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InscripcionService inscripcionService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("Sin autenticación responde 401 en los tres endpoints")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(post("/api/v1/torneos/3/inscripciones")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/inscripciones/7")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/inscripciones/7")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("Sin INSCRIPCION_GESTIONAR, crear y cancelar responden 403 SIN_PERMISO")
    void sinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(delete("/api/v1/inscripciones/7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("POST /torneos/{id}/inscripciones crea con el usuario de la sesión y responde 201 con Location")
    void crea() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.crear(PARTICIPANTE, 3L, null)).thenReturn(detalle());

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/inscripciones/7"))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.torneoId").value(3))
                .andExpect(jsonPath("$.estado").value("BORRADOR"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Una regla incumplida llega como 409 con su código")
    void reglaIncumplida() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.crear(PARTICIPANTE, 3L, null))
                .thenThrow(new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA, "cerrado"));

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ABIERTA"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /inscripciones/{id} alcanza con estar logueado: lo ve también el organizador")
    void ve() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.detalle(PARTICIPANTE, 7L)).thenReturn(detalle());

        mockMvc.perform(get("/api/v1/inscripciones/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.usuarioId").value(PARTICIPANTE));
    }

    @Test
    @WithMockUser
    @DisplayName("GET de una inscripción ajena responde 404 INSCRIPCION_NO_ENCONTRADA")
    void ajena() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.detalle(PARTICIPANTE, 7L))
                .thenThrow(new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "no existe"));

        mockMvc.perform(get("/api/v1/inscripciones/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ENCONTRADA"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("DELETE /inscripciones/{id} cancela con el usuario de la sesión y responde 204")
    void cancela() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(delete("/api/v1/inscripciones/7")).andExpect(status().isNoContent());

        verify(inscripcionService).cancelar(PARTICIPANTE, 7L);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("DELETE de una inscripción en otro estado responde 409 INSCRIPCION_NO_EDITABLE")
    void noSeCancela() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        doThrow(new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE, "confirmada"))
                .when(inscripcionService).cancelar(PARTICIPANTE, 7L);

        mockMvc.perform(delete("/api/v1/inscripciones/7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_EDITABLE"));
    }

    @Test
    @DisplayName("PUT /inscripciones/{id}/confirmar sin autenticación responde 401")
    void confirmarSinAutenticacion() throws Exception {
        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("PUT /inscripciones/{id}/confirmar sin INSCRIPCION_GESTIONAR responde 403 SIN_PERMISO")
    void confirmarSinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reglamentoAceptado\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("PUT /inscripciones/{id}/confirmar confirma con el usuario de la sesión y responde 200")
    void confirma() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.confirmar(PARTICIPANTE, 7L, true))
                .thenReturn(new InscripcionDetalle(7L, 3L, PARTICIPANTE, EstadoInscripcion.PENDIENTE_PAGO, CREADA, CREADA, CREADA, null, null));

        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reglamentoAceptado\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE_PAGO"))
                .andExpect(jsonPath("$.reglamentoAceptadoEn").value("2026-10-24T12:00:00Z"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Sin cuerpo, sin el campo o con false el reglamento cuenta como no aceptado")
    void sinAceptarElReglamento() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.confirmar(PARTICIPANTE, 7L, false))
                .thenThrow(new ConflictoException(CodigoError.REGLAMENTO_NO_ACEPTADO, "falta aceptar"));

        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("REGLAMENTO_NO_ACEPTADO"));
        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reglamentoAceptado\":false}"))
                .andExpect(status().isConflict());
        verify(inscripcionService, times(3)).confirmar(PARTICIPANTE, 7L, false);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Confirmar una inscripción ajena responde 404 y sin cupo responde 409 CUPO_COMPLETO")
    void confirmarConError() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.confirmar(PARTICIPANTE, 7L, true))
                .thenThrow(new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "no existe"));
        when(inscripcionService.confirmar(PARTICIPANTE, 8L, true))
                .thenThrow(new ConflictoException(CodigoError.CUPO_COMPLETO, "lleno"));
        String cuerpo = "{\"reglamentoAceptado\":true}";

        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ENCONTRADA"));
        mockMvc.perform(put("/api/v1/inscripciones/8/confirmar").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CUPO_COMPLETO"));
    }

    @Test
    @DisplayName("GET resumen-pago y POST pago sin autenticación responden 401")
    void pagoSinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/inscripciones/7/resumen-pago")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/inscripciones/7/pago")).andExpect(status().isUnauthorized());
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("Sin INSCRIPCION_GESTIONAR, el resumen y el pago responden 403 SIN_PERMISO")
    void pagoSinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(get("/api/v1/inscripciones/7/resumen-pago"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        mockMvc.perform(post("/api/v1/inscripciones/7/pago"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("GET /inscripciones/{id}/resumen-pago responde 200 con lo que se va a pagar")
    void resumenDePago() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.resumenPago(PARTICIPANTE, 7L)).thenReturn(new ResumenPago(7L,
                new ResumenPago.TorneoPago(3L, "Copa"), new UsuarioPublico(PARTICIPANTE, "jugador", "Ana", "Paz"), null,
                new BigDecimal("1500.00"), "ARS", 8, 5L, CREADA));

        mockMvc.perform(get("/api/v1/inscripciones/7/resumen-pago"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inscripcionId").value(7))
                .andExpect(jsonPath("$.torneo.nombre").value("Copa"))
                .andExpect(jsonPath("$.participante.username").value("jugador"))
                .andExpect(jsonPath("$.participante.email").doesNotExist())
                .andExpect(jsonPath("$.monto").value(1500.00))
                .andExpect(jsonPath("$.moneda").value("ARS"))
                .andExpect(jsonPath("$.cupoMaximo").value(8))
                .andExpect(jsonPath("$.cupoDisponible").value(5))
                .andExpect(jsonPath("$.cierreInscripcionEn").value("2026-10-24T12:00:00Z"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("El resumen de una inscripción ajena responde 404 y fuera de PENDIENTE_PAGO responde 409 con su código")
    void resumenConError() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.resumenPago(PARTICIPANTE, 7L))
                .thenThrow(new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "no existe"));
        when(inscripcionService.resumenPago(PARTICIPANTE, 8L))
                .thenThrow(new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE, "confirmada"));

        mockMvc.perform(get("/api/v1/inscripciones/7/resumen-pago"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ENCONTRADA"));
        mockMvc.perform(get("/api/v1/inscripciones/8/resumen-pago"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("POST /inscripciones/{id}/pago paga con el usuario de la sesión y responde 200")
    void paga() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.pagar(PARTICIPANTE, 7L))
                .thenReturn(new InscripcionDetalle(7L, 3L, PARTICIPANTE, EstadoInscripcion.CONFIRMADA, CREADA, CREADA, CREADA, null, null));

        mockMvc.perform(post("/api/v1/inscripciones/7/pago"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("El pago deja pasar el código de cada regla: 404, cupo completo y cobro rechazado")
    void pagoConError() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.pagar(PARTICIPANTE, 7L))
                .thenThrow(new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA, "no existe"));
        when(inscripcionService.pagar(PARTICIPANTE, 8L))
                .thenThrow(new ConflictoException(CodigoError.CUPO_COMPLETO, "lleno"));
        when(inscripcionService.pagar(PARTICIPANTE, 9L)).thenThrow(new CobroRechazadoException("sin fondos"));

        mockMvc.perform(post("/api/v1/inscripciones/7/pago"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_ENCONTRADA"));
        mockMvc.perform(post("/api/v1/inscripciones/8/pago"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("CUPO_COMPLETO"));
        mockMvc.perform(post("/api/v1/inscripciones/9/pago"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("COBRO_RECHAZADO"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("POST en un torneo por equipos pasa el equipo y la nómina y responde 201 con el equipo y la nómina")
    void creaElBorradorDeEquipo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.crear(eq(PARTICIPANTE), eq(3L), any())).thenReturn(borradorDeEquipo());

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"equipoId\":4,\"nomina\":[5,6]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/inscripciones/7"))
                .andExpect(jsonPath("$.usuarioId").doesNotExist())
                .andExpect(jsonPath("$.equipo.id").value(4))
                .andExpect(jsonPath("$.equipo.nombre").value("Los Halcones"))
                .andExpect(jsonPath("$.nomina[0].username").value("capitan"))
                .andExpect(jsonPath("$.nomina[0].email").doesNotExist());

        ArgumentCaptor<InscripcionEquipoRequest> cuerpo = ArgumentCaptor.forClass(InscripcionEquipoRequest.class);
        verify(inscripcionService).crear(eq(PARTICIPANTE), eq(3L), cuerpo.capture());
        assertThat(cuerpo.getValue()).isEqualTo(new InscripcionEquipoRequest(4L, null, List.of(5L, 6L)));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Un nombre de equipo nuevo demasiado largo responde 400 VALIDACION con el campo")
    void nombreDeEquipoLargo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(post("/api/v1/torneos/3/inscripciones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreEquipoNuevo\":\"" + "x".repeat(101) + "\",\"nomina\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("nombreEquipoNuevo"));
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Equipo ajeno, jugador ya inscripto y equipo duplicado llegan con su código")
    void reglasDelBorradorDeEquipo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.crear(eq(PARTICIPANTE), eq(1L), any()))
                .thenThrow(new NoEncontradoException(CodigoError.EQUIPO_NO_ENCONTRADO, "no es tuyo"));
        when(inscripcionService.crear(eq(PARTICIPANTE), eq(2L), any()))
                .thenThrow(new ConflictoException(CodigoError.JUGADOR_YA_INSCRIPTO, "ya figura"));
        when(inscripcionService.crear(eq(PARTICIPANTE), eq(3L), any()))
                .thenThrow(new ConflictoException(CodigoError.INSCRIPCION_DUPLICADA, "ya tiene"));
        String cuerpo = "{\"equipoId\":4,\"nomina\":[]}";

        mockMvc.perform(post("/api/v1/torneos/1/inscripciones").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("EQUIPO_NO_ENCONTRADO"));
        mockMvc.perform(post("/api/v1/torneos/2/inscripciones").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("JUGADOR_YA_INSCRIPTO"));
        mockMvc.perform(post("/api/v1/torneos/3/inscripciones").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("INSCRIPCION_DUPLICADA"));
    }

    @Test
    @DisplayName("PUT /inscripciones/{id} sin autenticación responde 401")
    void editarSinAutenticacion() throws Exception {
        mockMvc.perform(put("/api/v1/inscripciones/7").contentType(MediaType.APPLICATION_JSON).content("{\"equipoId\":4}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "ROL_VER")
    @DisplayName("PUT /inscripciones/{id} sin INSCRIPCION_GESTIONAR responde 403 SIN_PERMISO")
    void editarSinPermiso() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));

        mockMvc.perform(put("/api/v1/inscripciones/7").contentType(MediaType.APPLICATION_JSON).content("{\"equipoId\":4}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
        verifyNoInteractions(inscripcionService);
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("PUT /inscripciones/{id} reemplaza el equipo y la nómina con el usuario de la sesión y responde 200")
    void edita() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.editar(eq(PARTICIPANTE), eq(7L), any())).thenReturn(borradorDeEquipo());

        mockMvc.perform(put("/api/v1/inscripciones/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreEquipoNuevo\":\"Los Zorros\",\"nomina\":[5]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.nomina[0].username").value("capitan"));

        ArgumentCaptor<InscripcionEquipoRequest> cuerpo = ArgumentCaptor.forClass(InscripcionEquipoRequest.class);
        verify(inscripcionService).editar(eq(PARTICIPANTE), eq(7L), cuerpo.capture());
        assertThat(cuerpo.getValue()).isEqualTo(new InscripcionEquipoRequest(null, "Los Zorros", List.of(5L)));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("PUT sin cuerpo responde 400 y fuera de BORRADOR responde 409 INSCRIPCION_NO_EDITABLE")
    void editarConError() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.editar(eq(PARTICIPANTE), eq(7L), any()))
                .thenThrow(new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE, "confirmada"));

        mockMvc.perform(put("/api/v1/inscripciones/7")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/inscripciones/7").contentType(MediaType.APPLICATION_JSON).content("{\"equipoId\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("INSCRIPCION_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("El resumen de pago de un equipo lleva el equipo y no el participante")
    void resumenDePagoDeEquipo() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.resumenPago(PARTICIPANTE, 7L)).thenReturn(new ResumenPago(7L,
                new ResumenPago.TorneoPago(3L, "Copa"), null, new InscripcionDetalle.EquipoInscripto(4L, "Los Halcones"),
                new BigDecimal("1500.00"), "ARS", 8, 5L, CREADA));

        mockMvc.perform(get("/api/v1/inscripciones/7/resumen-pago"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equipo.id").value(4))
                .andExpect(jsonPath("$.equipo.nombre").value("Los Halcones"))
                .andExpect(jsonPath("$.participante").doesNotExist());
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Confirmar con una nómina inválida responde 409 NOMINA_INVALIDA")
    void confirmarConNominaInvalida() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.confirmar(PARTICIPANTE, 7L, true))
                .thenThrow(new ConflictoException(CodigoError.NOMINA_INVALIDA, "faltan jugadores"));

        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reglamentoAceptado\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("NOMINA_INVALIDA"));
    }

    @Test
    @WithMockUser(authorities = "INSCRIPCION_GESTIONAR")
    @DisplayName("Confirmar con un jugador que ya figura en otra inscripción responde 409 JUGADOR_YA_INSCRIPTO")
    void confirmarConJugadorYaInscripto() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(PARTICIPANTE));
        when(inscripcionService.confirmar(PARTICIPANTE, 7L, true))
                .thenThrow(new ConflictoException(CodigoError.JUGADOR_YA_INSCRIPTO, "ya figura"));

        mockMvc.perform(put("/api/v1/inscripciones/7/confirmar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reglamentoAceptado\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("JUGADOR_YA_INSCRIPTO"));
    }

    private InscripcionDetalle borradorDeEquipo() {
        return new InscripcionDetalle(7L, 3L, null, EstadoInscripcion.BORRADOR, null, CREADA, CREADA,
                new InscripcionDetalle.EquipoInscripto(4L, "Los Halcones"),
                List.of(new UsuarioPublico(PARTICIPANTE, "capitan", "Ana", "Paz")));
    }

    private InscripcionDetalle detalle() {
        return new InscripcionDetalle(7L, 3L, PARTICIPANTE, EstadoInscripcion.BORRADOR, null, CREADA, CREADA, null, null);
    }
}
