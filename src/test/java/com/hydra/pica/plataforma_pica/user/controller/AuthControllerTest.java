package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.stream.Stream;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.service.DatosPersona;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import com.hydra.pica.plataforma_pica.user.service.VerificacionEmailService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, WebConfig.class})
class AuthControllerTest {

    private final MockMvc mockMvc;

    @MockitoBean
    private UsuarioService usuarioService;

    @MockitoBean
    private VerificacionEmailService verificacionEmailService;

    @Autowired
    AuthControllerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @DisplayName("Registro válido: 201 con el id creado y los datos esperados")
    void registroValido() throws Exception {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getId()).thenReturn(42L);
        when(usuarioService.crear(any(NuevoUsuario.class))).thenReturn(usuario);

        mockMvc.perform(post("/api/v1/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyValido()))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(42));

        var captor = org.mockito.ArgumentCaptor.forClass(NuevoUsuario.class);
        verify(usuarioService).crear(captor.capture());
        NuevoUsuario nuevo = captor.getValue();
        assertThat(nuevo.username()).isEqualTo("jperez");
        assertThat(nuevo.email()).isEqualTo("juan.perez@example.com");
        assertThat(nuevo.password()).isEqualTo("Pica2026");
        assertThat(nuevo.datosPersona()).isEqualTo(new DatosPersona(
                TipoDoc.DNI,
                "30123456",
                "Juan",
                "Pérez",
                LocalDate.of(1990, 5, 17),
                null,
                null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cuerposInvalidos")
    @DisplayName("Registro inválido: 400 VALIDACION con el campo correspondiente")
    void registroInvalido(String descripcion, String body, String campo, String codigo) throws Exception {
        mockMvc.perform(post("/api/v1/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value(campo))
                .andExpect(jsonPath("$.errores[0].codigo").value(codigo));
    }

    @Test
    @DisplayName("Password de más de 72 bytes: 400 PASSWORD_DEBIL")
    void passwordSuperaLimiteDe72Bytes() throws Exception {
        String password = "A" + "a".repeat(71) + "1";

        mockMvc.perform(post("/api/v1/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyCon("password", password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("password"))
                .andExpect(jsonPath("$.errores[0].codigo").value("PASSWORD_DEBIL"));
    }

    static Stream<Arguments> cuerposInvalidos() {
        return Stream.of(
                Arguments.of("username con espacio",
                        bodyCon("username", "juan perez"), "username", "FORMATO_INVALIDO"),
                Arguments.of("email sin arroba",
                        bodyCon("email", "juan.example.com"), "email", "FORMATO_INVALIDO"),
                Arguments.of("password sin mayúscula",
                        bodyCon("password", "pica2026"), "password", "PASSWORD_DEBIL"),
                Arguments.of("password sin dígito",
                        bodyCon("password", "Picapica"), "password", "PASSWORD_DEBIL"),
                Arguments.of("password de siete caracteres",
                        bodyCon("password", "Pica202"), "password", "PASSWORD_DEBIL"),
                Arguments.of("nroDoc con guion",
                        bodyCon("nroDoc", "30-123456"), "nroDoc", "FORMATO_INVALIDO"),
                Arguments.of("fecha de nacimiento futura",
                        bodyCon("fechaNacimiento", "2999-01-01"), "fechaNacimiento", "FECHA_FUTURA"),
                Arguments.of("tipoDoc nulo",
                        bodyCon("tipoDoc", null), "tipoDoc", "REQUERIDO"));
    }

    @Test
    @DisplayName("Username duplicado: 409 USERNAME_DUPLICADO propagado al handler")
    void usernameDuplicado() throws Exception {
        when(usuarioService.crear(any(NuevoUsuario.class)))
                .thenThrow(new ConflictoException(CodigoError.USERNAME_DUPLICADO, "ya existe"));

        mockMvc.perform(post("/api/v1/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyValido()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("USERNAME_DUPLICADO"));
    }

    @Test
    @DisplayName("Verificación válida: 204 y delega el token")
    void verificarValido() throws Exception {
        mockMvc.perform(get("/api/v1/auth/verificar").param("token", "token-plano"))
                .andExpect(status().isNoContent());

        verify(verificacionEmailService).verificar("token-plano");
    }

    @Test
    @DisplayName("Verificación repetida con el mismo token: 400 TOKEN_USADO")
    void verificarMismoTokenDosVeces() throws Exception {
        doNothing().doThrow(new ApiException(
                HttpStatus.BAD_REQUEST,
                CodigoError.TOKEN_USADO,
                "El token ya fue utilizado"))
                .when(verificacionEmailService)
                .verificar("token-plano");

        mockMvc.perform(get("/api/v1/auth/verificar").param("token", "token-plano"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/verificar").param("token", "token-plano"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TOKEN_USADO"));
    }

    @Test
    @DisplayName("Reenvío: 202 y delega el email")
    void reenviarVerificacion() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reenviar-verificacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"juan.perez@example.com"}
                                """))
                .andExpect(status().isAccepted());

        verify(verificacionEmailService).reenviar("juan.perez@example.com");
    }

    @Test
    @DisplayName("Reenvío inválido: 400 VALIDACION")
    void reenviarVerificacionInvalido() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reenviar-verificacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"no-es-un-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    private static String bodyValido() {
        return """
                {
                    "username": "jperez",
                    "email": "juan.perez@example.com",
                    "password": "Pica2026",
                    "nombres": "Juan",
                    "apellidos": "Pérez",
                    "tipoDoc": "DNI",
                    "nroDoc": "30123456",
                    "fechaNacimiento": "1990-05-17"
                }
                """;
    }

    private static String bodyCon(String campo, String valor) {
        String valorJson = valor == null ? "null" : "\"" + valor + "\"";
        return bodyValido().replace("\"" + valorActual(campo) + "\"", valorJson);
    }

    private static String valorActual(String campo) {
        return switch (campo) {
            case "username" -> "jperez";
            case "email" -> "juan.perez@example.com";
            case "password" -> "Pica2026";
            case "nroDoc" -> "30123456";
            case "fechaNacimiento" -> "1990-05-17";
            case "tipoDoc" -> "DNI";
            default -> throw new IllegalArgumentException("Campo no soportado: " + campo);
        };
    }
}
