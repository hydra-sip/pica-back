package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.event.UsuarioCreado;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class VerificacionEmailServiceIntegracionTest {

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private VerificacionEmailService verificacionEmailService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PersonaRepository personaRepository;

    @MockitoBean
    private EmailService emailService;

    private final Set<Long> usuariosCreados = new HashSet<>();
    private final Set<Long> personasCreadas = new HashSet<>();

    @AfterEach
    void limpiarDatosCreados() {
        usuariosCreados.forEach(id -> usuarioRepository.findById(id).ifPresent(usuario -> {
            usuario.setEliminadoEn(Instant.now());
            usuarioRepository.saveAndFlush(usuario);
        }));
        personasCreadas.forEach(id -> personaRepository.findById(id).ifPresent(persona -> {
            persona.setEliminadoEn(Instant.now());
            personaRepository.saveAndFlush(persona);
        }));
        usuariosCreados.clear();
        personasCreadas.clear();
    }

    @Test
    void autorregistroConfirmaListenerYEmiteToken() {
        String sufijo = sufijo();
        String email = "verificacion-" + sufijo + "@example.com";

        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro(
                "verificacion" + sufijo,
                email,
                "Pica2026",
                new DatosPersona(TipoDoc.DNI, documento(), "Juan", "Pérez", null, null, null)));
        registrar(creado);

        Usuario leido = usuarioRepository.findById(creado.getId()).orElseThrow();

        assertThat(leido.getTokenVerificacionHash()).isNotNull();
        assertThat(leido.getTokenVerificacionExpiraEn()).isNotNull();
        verify(emailService).enviarVerificacion(
                eq(email),
                startsWith("http://localhost:8080/api/v1/auth/verificar?token="));
    }

    @Test
    void tokenUsadoDosVeces() {
        String sufijo = sufijo();
        String email = "token-usado-" + sufijo + "@example.com";

        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro(
                "tokenusado" + sufijo,
                email,
                "Pica2026",
                new DatosPersona(TipoDoc.DNI, documento(), "Juan", "Pérez", null, null, null)));
        registrar(creado);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).enviarVerificacion(eq(email), urlCaptor.capture());
        String url = urlCaptor.getValue();
        assertThat(url).startsWith("http://localhost:8080/api/v1/auth/verificar?token=");
        String token = extraerToken(url);

        verificacionEmailService.verificar(token);

        Usuario verificado = usuarioRepository.findById(creado.getId()).orElseThrow();
        assertThat(verificado.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);

        assertThatThrownBy(() -> verificacionEmailService.verificar(token))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCodigo()).isEqualTo(CodigoError.TOKEN_USADO);
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void tokenVencido() {
        String sufijo = sufijo();
        String email = "token-vencido-" + sufijo + "@example.com";

        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro(
                "tokenvencido" + sufijo,
                email,
                "Pica2026",
                new DatosPersona(TipoDoc.DNI, documento(), "Juan", "Pérez", null, null, null)));
        registrar(creado);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).enviarVerificacion(eq(email), urlCaptor.capture());
        String url = urlCaptor.getValue();
        assertThat(url).startsWith("http://localhost:8080/api/v1/auth/verificar?token=");
        String token = extraerToken(url);

        Usuario conTokenVencido = usuarioRepository.findById(creado.getId()).orElseThrow();
        conTokenVencido.setTokenVerificacionExpiraEn(Instant.now().minusSeconds(1));
        usuarioRepository.saveAndFlush(conTokenVencido);

        assertThatThrownBy(() -> verificacionEmailService.verificar(token))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getCodigo()).isEqualTo(CodigoError.TOKEN_EXPIRADO);
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void altaGoogleNoEmiteTokenNiEmail() {
        String sufijo = sufijo();
        String email = "google-" + sufijo + "@example.com";

        Usuario creado = usuarioService.crear(
                NuevoUsuario.desdeGoogle(email, "google-" + sufijo, "Juan", "Pérez"));
        registrar(creado);

        Usuario leido = usuarioRepository.findById(creado.getId()).orElseThrow();

        assertThat(leido.getTokenVerificacionHash()).isNull();
        assertThat(leido.getTokenVerificacionExpiraEn()).isNull();
        verify(emailService, never()).enviarVerificacion(anyString(), anyString());
    }

    @Test
    void usuarioActivoNoEmiteTokenAunqueElEventoRequieraVerificacion() {
        String sufijo = sufijo();
        Usuario creado = usuarioService.crear(
                NuevoUsuario.desdeGoogle("activo-" + sufijo + "@example.com", "activo-" + sufijo,
                        "Juan", "Pérez"));
        registrar(creado);

        verificacionEmailService.alCrearUsuario(new UsuarioCreado(
                creado.getId(), creado.getUsername(), creado.getEmail(), true));

        Usuario leido = usuarioRepository.findById(creado.getId()).orElseThrow();

        assertThat(leido.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(leido.getTokenVerificacionHash()).isNull();
        assertThat(leido.getTokenVerificacionExpiraEn()).isNull();
        verify(emailService, never()).enviarVerificacion(anyString(), anyString());
    }

    private void registrar(Usuario usuario) {
        usuariosCreados.add(usuario.getId());
        personasCreadas.add(usuario.getPersona().getId());
    }

    private static String extraerToken(String url) {
        String query = URI.create(url).getQuery();
        return java.util.Arrays.stream(query.split("&"))
                .map(par -> par.split("=", 2))
                .filter(par -> par[0].equals("token"))
                .map(par -> par[1])
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "La URL no tiene el parámetro token: " + url));
    }

    private static String sufijo() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String documento() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
