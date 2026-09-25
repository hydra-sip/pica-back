package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.config.JwtTestSupportConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.dto.CambioPasswordRequest;
import com.hydra.pica.plataforma_pica.user.dto.Me;
import com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest;
import com.hydra.pica.plataforma_pica.user.dto.PersonaDatos;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
import com.hydra.pica.plataforma_pica.user.service.PerfilService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET y PUT /api/v1/me. Hasta que llegue el filtro JWT (HU-102) el id del usuario logueado sale de
 * un {@link CurrentUserProvider} mockeado; {@code @WithMockUser} solo cubre el "está autenticado".
 */
@WebMvcTest(MeController.class)
@ActiveProfiles("dev")
@Import({SecurityConfig.class, WebConfig.class, JwtTestSupportConfiguration.class})
class MeControllerTest {

    private static final Long ID = 5L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PerfilService perfilService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @Test
    @DisplayName("GET /api/v1/me sin autenticación responde 401")
    void sinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(perfilService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/me autenticado pero sin id de usuario en la sesión responde 401 NO_AUTENTICADO")
    void sinIdDeUsuario() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
        verifyNoInteractions(perfilService);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/me devuelve el usuario logueado con persona, roles, permisos y datosCompletos")
    void devuelveElMe() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));
        when(perfilService.obtener(ID)).thenReturn(me());

        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.id").value(5))
                .andExpect(jsonPath("$.usuario.username").value("jperez"))
                .andExpect(jsonPath("$.usuario.email").value("jperez@example.com"))
                .andExpect(jsonPath("$.usuario.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.usuario.tieneContrasena").value(false))
                .andExpect(jsonPath("$.persona.id").value(10))
                .andExpect(jsonPath("$.persona.nombres").value("Juan"))
                .andExpect(jsonPath("$.persona.tipoDoc").isEmpty())
                .andExpect(jsonPath("$.persona.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.roles[0].nombre").value("PARTICIPANTE"))
                .andExpect(jsonPath("$.permisos[0]").value("PERSONA_VER"))
                .andExpect(jsonPath("$.datosCompletos").value(false));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me pasa el body al servicio con el id de la sesión y devuelve el Me actualizado")
    void actualiza() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));
        when(perfilService.actualizar(eq(ID), any(MeUpdateRequest.class))).thenReturn(me());

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "fechaNacimiento": "1990-05-20",
                                 "domicilioPostal": "Calle 123", "telefono": null,
                                 "tipoDoc": "DNI", "nroDoc": "30123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.id").value(5));

        ArgumentCaptor<MeUpdateRequest> captor = ArgumentCaptor.forClass(MeUpdateRequest.class);
        verify(perfilService).actualizar(eq(ID), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new MeUpdateRequest(
                "Juan", "Pérez", LocalDate.of(1990, 5, 20), "Calle 123", null, TipoDoc.DNI, "30123456"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me sin nombres responde 400 VALIDACION marcando el campo")
    void faltanNombres() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": " ", "apellidos": "Pérez"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("nombres"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));
        verify(perfilService, never()).actualizar(any(), any());
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me con fecha de nacimiento futura responde 400 FECHA_FUTURA")
    void fechaFutura() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "fechaNacimiento": "%s"}
                                """.formatted(LocalDate.now().plusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("fechaNacimiento"))
                .andExpect(jsonPath("$.errores[0].codigo").value("FECHA_FUTURA"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me con nroDoc con guiones responde 400 FORMATO_INVALIDO")
    void nroDocConGuiones() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "tipoDoc": "DNI", "nroDoc": "30-123-456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nroDoc"))
                .andExpect(jsonPath("$.errores[0].codigo").value("FORMATO_INVALIDO"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me con un DNI con letras responde 400 FORMATO_INVALIDO en nroDoc")
    void dniConLetras() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "tipoDoc": "DNI", "nroDoc": "ABCDE12"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("nroDoc"))
                .andExpect(jsonPath("$.errores[0].codigo").value("FORMATO_INVALIDO"));
        verifyNoInteractions(perfilService);
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me con el nroDoc vacío (formulario sin completar) se toma como sin documento")
    void nroDocVacioEsSinDocumento() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));
        when(perfilService.actualizar(eq(ID), any())).thenReturn(me());

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "tipoDoc": null, "nroDoc": ""}
                                """))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest.class);
        verify(perfilService).actualizar(eq(ID), captor.capture());
        assertThat(captor.getValue().nroDoc()).isNull();
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me con un pasaporte alfanumérico se acepta")
    void pasaporteAlfanumerico() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));
        when(perfilService.actualizar(eq(ID), any())).thenReturn(me());

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "tipoDoc": "PASAPORTE", "nroDoc": "AAB123456"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me con un tipoDoc que no existe responde 400 VALIDACION")
    void tipoDocInexistente() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "tipoDoc": "CUIT", "nroDoc": "30123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me cuando el documento ya está cargado responde 409 DOCUMENTO_NO_EDITABLE")
    void documentoNoEditable() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));
        when(perfilService.actualizar(eq(ID), any(MeUpdateRequest.class)))
                .thenThrow(new ConflictoException(CodigoError.DOCUMENTO_NO_EDITABLE, "ya tiene"));

        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombres": "Juan", "apellidos": "Pérez", "tipoDoc": "DNI", "nroDoc": "40111222"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("DOCUMENTO_NO_EDITABLE"));
    }

    // --- PUT /me/password (PICA-122) ---------------------------------------------

    private static final String CAMBIO = """
            {"passwordActual": "Actual123", "passwordNueva": "Nueva1234"}
            """;

    @Test
    @DisplayName("PUT /api/v1/me/password sin autenticación responde 401")
    void passwordSinAutenticacion() throws Exception {
        mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content(CAMBIO))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(perfilService);
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me/password autenticado pero sin id de usuario en la sesión responde 401 NO_AUTENTICADO")
    void passwordSinIdDeUsuario() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content(CAMBIO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
        verifyNoInteractions(perfilService);
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me/password pasa las dos contraseñas al servicio con el id de la sesión y responde 204")
    void cambiaLaPassword() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content(CAMBIO))
                .andExpect(status().isNoContent());

        verify(perfilService).cambiarPassword(ID, new CambioPasswordRequest("Actual123", "Nueva1234"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me/password sin passwordActual (usuario de Google) llega al servicio con null y responde 204")
    void definePasswordSinActual() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwordNueva\": \"Nueva1234\"}"))
                .andExpect(status().isNoContent());

        verify(perfilService).cambiarPassword(ID, new CambioPasswordRequest(null, "Nueva1234"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me/password con la contraseña nueva ausente, en blanco, débil o demasiado larga responde 400 con el campo")
    void passwordNuevaInvalida() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));
        String[][] casos = {
                {"{\"passwordActual\": \"Actual123\"}", "REQUERIDO"},
                {"{\"passwordActual\": \"Actual123\", \"passwordNueva\": \"   \"}", "REQUERIDO"},
                {"{\"passwordActual\": \"Actual123\", \"passwordNueva\": \"Corta1\"}", "PASSWORD_DEBIL"},
                {"{\"passwordActual\": \"Actual123\", \"passwordNueva\": \"sinmayuscula1\"}", "PASSWORD_DEBIL"},
                {"{\"passwordActual\": \"Actual123\", \"passwordNueva\": \"SinNumeroNiNada\"}", "PASSWORD_DEBIL"},
                {"{\"passwordActual\": \"Actual123\", \"passwordNueva\": \"" + "Aa1".repeat(25) + "\"}", "LONGITUD"},
        };
        for (String[] caso : casos) {
            mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content(caso[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                    .andExpect(jsonPath("$.errores[0].campo").value("passwordNueva"))
                    .andExpect(jsonPath("$.errores[?(@.campo == 'passwordNueva')].codigo").value(hasItem(caso[1])));
        }
        verifyNoInteractions(perfilService);
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me/password con una passwordActual de más de 72 caracteres responde 400 en ese campo")
    void passwordActualDemasiadoLarga() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));

        mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwordActual\": \"" + "a".repeat(73) + "\", \"passwordNueva\": \"Nueva1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("passwordActual"))
                .andExpect(jsonPath("$.errores[0].codigo").value("LONGITUD"));
        verifyNoInteractions(perfilService);
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/v1/me/password: los errores del servicio salen con su código (400 actual incorrecta, 403 bloqueado)")
    void erroresDelServicio() throws Exception {
        when(currentUserProvider.getCurrentUserId()).thenReturn(Optional.of(ID));
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, CodigoError.PASSWORD_ACTUAL_INCORRECTA, "no coincide"))
                .doThrow(new ApiException(HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO, "bloqueado"))
                .when(perfilService).cambiarPassword(eq(ID), any());

        mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content(CAMBIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_ACTUAL_INCORRECTA"));
        mockMvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content(CAMBIO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_BLOQUEADO"));
    }

    private static Me me() {
        return new Me(
                new Me.DatosUsuario(ID, "jperez", "jperez@example.com", EstadoUsuario.ACTIVO, false),
                new PersonaDatos(10L, "Juan", "Pérez", null, null, null, null, null, null, EstadoGeneral.ACTIVO),
                List.of(new RolMinimo(2L, "PARTICIPANTE", "Participante")),
                List.of("PERSONA_VER"),
                false);
    }
}
