package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.stream.Stream;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.service.DatosPersona;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, WebConfig.class})
class AuthControllerTest {

    private final MockMvc mockMvc;

    @MockBean
    private UsuarioService usuarioService;

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
    void registroInvalido(String descripcion, String body, String campo) throws Exception {
        mockMvc.perform(post("/api/v1/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[?(@.campo=='" + campo + "')]").isNotEmpty());
    }

    static Stream<Arguments> cuerposInvalidos() {
        return Stream.of(
                Arguments.of("username con espacio",
                        bodyCon("username", "juan perez"), "username"),
                Arguments.of("email sin arroba",
                        bodyCon("email", "juan.example.com"), "email"),
                Arguments.of("password sin mayúscula",
                        bodyCon("password", "pica2026"), "password"),
                Arguments.of("password sin dígito",
                        bodyCon("password", "Picapica"), "password"),
                Arguments.of("password de siete caracteres",
                        bodyCon("password", "Pica202"), "password"),
                Arguments.of("nroDoc con guion",
                        bodyCon("nroDoc", "30-123456"), "nroDoc"),
                Arguments.of("fecha de nacimiento futura",
                        bodyCon("fechaNacimiento", "2999-01-01"), "fechaNacimiento"),
                Arguments.of("tipoDoc nulo",
                        bodyCon("tipoDoc", null), "tipoDoc"));
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
