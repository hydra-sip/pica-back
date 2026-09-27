package com.hydra.pica.plataforma_pica.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
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

// El application.yml de test no trae la registración de Google: sin esto no se arma el oauth2Login
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.google.client-id=client-id-de-test",
        "spring.security.oauth2.client.registration.google.client-secret=client-secret-de-test",
        "spring.security.oauth2.client.registration.google.scope=openid,profile,email"})
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

        Usuario usuario = authService.procesarLoginGoogle(googleSub, email, true, "Carlos", "Gómez");

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
        Usuario vinculo = authService.procesarLoginGoogle(googleSub, email, true, "Laura", "Fernández");

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
    @DisplayName("Iniciar el login redirige a Google")
    void iniciarLoginRedirigeAGoogle() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com/")));
    }

    @Test
    @DisplayName("Cancelar en Google vuelve al front con error=NO_AUTENTICADO")
    void cancelarEnGoogleVuelveAlFront() throws Exception {
        mockMvc.perform(get("/login/oauth2/code/google")
                        .param("error", "access_denied")
                        .param("state", "cualquiera"))
                .andExpect(redirectedUrl("http://localhost:5173/oauth/callback?error=NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("Mail sin verificar por Google: no vincula ni crea")
    void mailNoVerificadoNoVinculaNiCrea() {
        String email = "sin.verificar@example.com";
        DatosPersona persona = new DatosPersona(null, null, "Ana", "Pérez", null, null, null);
        Usuario registrado = usuarioService.crear(NuevoUsuario.autoRegistro("anasinverificar", email, "Pica2026", persona));

        assertThatThrownBy(() -> authService.procesarLoginGoogle("sub-google-3003", email, false, "Ana", "Pérez"))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_NO_VERIFICADO);
        assertThat(usuarioRepository.findById(registrado.getId()).orElseThrow().getGoogleSub()).isNull();

        assertThatThrownBy(() -> authService.procesarLoginGoogle("sub-google-3004", "nadie@example.com", false, "X", "Y"))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_NO_VERIFICADO);
        assertThat(usuarioRepository.findByEmailIgnoreCase("nadie@example.com")).isEmpty();
    }

    @Test
    @DisplayName("El usuario del mail ya tiene otra cuenta de Google: no entra ni se pisa")
    void otraCuentaDeGoogleNoEntra() {
        String email = "vinculado@example.com";
        Usuario usuario = authService.procesarLoginGoogle("sub-google-4004", email, true, "Juan", "Díaz");

        assertThatThrownBy(() -> authService.procesarLoginGoogle("sub-google-otra", email, true, "Juan", "Díaz"))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.CREDENCIALES_INVALIDAS);
        assertThat(usuarioRepository.findById(usuario.getId()).orElseThrow().getGoogleSub()).isEqualTo("sub-google-4004");
    }

    @Test
    @DisplayName("Usuario dado de baja: no se crea otro con su mail")
    void usuarioDadoDeBajaNoSeRecrea() {
        String email = "dado.de.baja@example.com";
        Usuario usuario = authService.procesarLoginGoogle("sub-google-5005", email, true, "Eva", "Ruiz");
        usuario.setEliminadoEn(Instant.now());
        usuarioRepository.saveAndFlush(usuario);

        assertThatThrownBy(() -> authService.procesarLoginGoogle("sub-google-5005", email, true, "Eva", "Ruiz"))
                .isInstanceOf(ApiException.class)
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_DUPLICADO);
    }

    @Test
    @DisplayName("Usuario bloqueado: el canje responde 403 USUARIO_BLOQUEADO")
    void usuarioBloqueadoNoCanjea() throws Exception {
        Usuario usuario = authService.procesarLoginGoogle("sub-google-6006", "bloqueado@example.com", true, "Leo", "Sosa");
        usuario.setEstado(EstadoUsuario.BLOQUEADO);
        usuarioRepository.saveAndFlush(usuario);

        String code = oAuthCodeStore.generarCodigo(usuario.getId());

        mockMvc.perform(post("/api/v1/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s"}
                                """.formatted(code)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("USUARIO_BLOQUEADO"));
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
