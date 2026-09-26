package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.security.OAuthCodeStore;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.service.AuthService;
import com.hydra.pica.plataforma_pica.user.service.DatosPersona;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class OAuthLoginIntegracionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private UsuarioService usuarioService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private OAuthCodeStore oAuthCodeStore;

    @Test
    @DisplayName("Flujo completo Google Login: procesar usuario nuevo, generar código y canjearlo por tokens")
    void flujoCompletoGoogleLoginUsuarioNuevo() throws Exception {
        String googleSub = "sub-google-1001";
        String email = "nuevo.google@example.com";

        Usuario usuario = authService.procesarLoginGoogle(googleSub, email, "Carlos", "Gómez");

        assertThat(usuario).isNotNull();
        assertThat(usuario.getGoogleSub()).isEqualTo(googleSub);
        assertThat(usuario.getEmail()).isEqualTo(email);
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(usuario.isEmailVerificado()).isTrue();
        assertThat(usuario.getPersona().getNombres()).isEqualTo("Carlos");
        assertThat(usuario.getPersona().getApellidos()).isEqualTo("Gómez");
        assertThat(usuario.getPersona().getNroDoc()).isNull();

        String code = oAuthCodeStore.generarCodigo(usuario.getId());

        mockMvc.perform(post("/api/v1/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));

        // Reutilización del código falla con 400 CODIGO_INVALIDO
        mockMvc.perform(post("/api/v1/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s"}
                                """.formatted(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("CODIGO_INVALIDO"));
    }

    @Test
    @DisplayName("Usuario existente por email vincula googleSub y canjea código")
    void vinculaUsuarioExistentePorEmail() throws Exception {
        String email = "existente.registro@example.com";
        DatosPersona persona = new DatosPersona(null, null, "Laura", "Fernández", null, null, null);
        Usuario registrado = usuarioService.crear(NuevoUsuario.autoRegistro("lauraf", email, "Pica2026", persona));

        String googleSub = "sub-google-2002";
        Usuario vinculo = authService.procesarLoginGoogle(googleSub, email, "Laura", "Fernández");

        assertThat(vinculo.getId()).isEqualTo(registrado.getId());
        assertThat(vinculo.getGoogleSub()).isEqualTo(googleSub);
        assertThat(vinculo.isEmailVerificado()).isTrue();

        String code = oAuthCodeStore.generarCodigo(vinculo.getId());

        mockMvc.perform(post("/api/v1/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("Canje de código inexistente o inválido responde 400 CODIGO_INVALIDO")
    void canjeCodigoInvalido() throws Exception {
        mockMvc.perform(post("/api/v1/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "codigo-fantasma-123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("CODIGO_INVALIDO"));
    }
}
