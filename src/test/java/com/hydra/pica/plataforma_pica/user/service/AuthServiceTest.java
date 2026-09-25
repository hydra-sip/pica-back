package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.RefreshToken;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.dto.LoginRequest;
import com.hydra.pica.plataforma_pica.user.dto.TokenPair;
import com.hydra.pica.plataforma_pica.user.event.RolesDeUsuarioCambiados;
import com.hydra.pica.plataforma_pica.user.event.SesionesDeUsuarioInvalidadas;
import com.hydra.pica.plataforma_pica.user.repository.RefreshTokenRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UsuarioRepository usuarioRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private PermisoService permisoService;

    private AuthService authService;
    private Usuario usuario;

    @BeforeEach
    void setUp() {
        authService = new AuthService(usuarioRepository, refreshTokenRepository, passwordEncoder, jwtService,
                permisoService);
        usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", 42L);
        usuario.setUsername("jperez");
        usuario.setEmail("juan@example.com");
        usuario.setPasswordHash("hash");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setRoles(Set.of());
    }

    @Test
    void loginExitosoEmiteParYGuardaRefreshHasheado() {
        when(usuarioRepository.findByUsernameIgnoreCase("jperez")).thenReturn(Optional.of(usuario));
        when(passwordEncoder.matches("Pica2026", "hash")).thenReturn(true);
        when(jwtService.generarAccessToken(42L, "jperez", java.util.List.<String>of(), java.util.List.<String>of()))
                .thenReturn("access");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TokenPair par = authService.login(new LoginRequest("jperez", "Pica2026"), "browser");

        assertThat(par.accessToken()).isEqualTo("access");
        assertThat(par.refreshToken()).isNotBlank();
        assertThat(par.tokenType()).isEqualTo("Bearer");
        assertThat(par.expiresIn()).isEqualTo(900);
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).hasSize(64);
        assertThat(captor.getValue().getUserAgent()).isEqualTo("browser");
    }

    @Test
    void loginBloqueadoRespondeCodigoEspecifico() {
        usuario.setEstado(EstadoUsuario.BLOQUEADO);
        when(usuarioRepository.findByUsernameIgnoreCase("jperez")).thenReturn(Optional.of(usuario));
        when(passwordEncoder.matches("Pica2026", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("jperez", "Pica2026"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getCodigo()).isEqualTo(CodigoError.USUARIO_BLOQUEADO);
                });
    }

    @Test
    void loginConCredencialesInvalidasResponde401() {
        when(usuarioRepository.findByUsernameIgnoreCase("jperez")).thenReturn(Optional.of(usuario));
        when(passwordEncoder.matches("incorrecta", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("jperez", "incorrecta"), null))
                .extracting("codigo").isEqualTo(CodigoError.CREDENCIALES_INVALIDAS);
    }

    @Test
    void refreshValidoRevocaElActualYGuardaElReemplazo() {
        RefreshToken actual = new RefreshToken();
        actual.setUsuario(usuario);
        actual.setTokenHash("hash");
        actual.setExpiraEn(Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any(String.class))).thenReturn(Optional.of(actual));
        when(usuarioRepository.existsByIdAndEstado(42L, EstadoUsuario.ACTIVO)).thenReturn(true);
        when(jwtService.generarAccessToken(42L, "jperez", java.util.List.<String>of(), java.util.List.<String>of()))
                .thenReturn("access-new");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TokenPair par = authService.refresh("refresh-old", "new-agent");

        assertThat(par.accessToken()).isEqualTo("access-new");
        assertThat(par.refreshToken()).isNotEqualTo("refresh-old");
        assertThat(actual.getRevocadoEn()).isNotNull();
        assertThat(actual.getReemplazadoPor()).hasSize(64);
        verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(any(RefreshToken.class));
    }

    @Test
    void refreshReutilizadoRevocaFamiliaDelUsuario() {
        RefreshToken token = new RefreshToken();
        token.setUsuario(usuario);
        token.setTokenHash("hash");
        token.setExpiraEn(Instant.now().plusSeconds(60));
        token.setRevocadoEn(Instant.now().minusSeconds(1));
        token.setReemplazadoPor("hash-del-siguiente");
        when(refreshTokenRepository.findByTokenHash(any(String.class))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refresh("refresh", null))
                .extracting("codigo").isEqualTo(CodigoError.REFRESH_REUTILIZADO);

        verify(refreshTokenRepository).revocarActivosPorUsuario(org.mockito.ArgumentMatchers.eq(42L), any(Instant.class));
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void logoutRevocaTokenActivo() {
        RefreshToken token = new RefreshToken();
        token.setRevocadoEn(null);
        when(refreshTokenRepository.findByTokenHash(any(String.class))).thenReturn(Optional.of(token));
        when(refreshTokenRepository.save(token)).thenReturn(token);

        authService.logout("refresh");

        assertThat(token.getRevocadoEn()).isNotNull();
        verify(refreshTokenRepository).save(token);
    }

    @Test
    void refreshCerradoSinRotarVencidoODeUsuarioNoActivoEsInvalidoYNoRevocaNada() {
        RefreshToken cerrado = token(Instant.now().plusSeconds(60));
        cerrado.setRevocadoEn(Instant.now().minusSeconds(1));
        RefreshToken vencido = token(Instant.now().minusSeconds(1));
        RefreshToken deBloqueado = token(Instant.now().plusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any(String.class)))
                .thenReturn(Optional.of(cerrado), Optional.of(vencido), Optional.of(deBloqueado));
        when(usuarioRepository.existsByIdAndEstado(42L, EstadoUsuario.ACTIVO)).thenReturn(false);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authService.refresh("refresh", null))
                    .extracting("codigo").isEqualTo(CodigoError.REFRESH_INVALIDO);
        }
        verify(refreshTokenRepository, never()).revocarActivosPorUsuario(any(), any());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void elTokenLlevaSoloLosRolesActivosYLosPermisosDePermisoService() {
        usuario.setRoles(Set.of(
                new UsuarioRol(usuario, rol(1L, "ORGANIZADOR", EstadoGeneral.ACTIVO)),
                new UsuarioRol(usuario, rol(2L, "SOPORTE", EstadoGeneral.INACTIVO))));
        when(usuarioRepository.findByUsernameIgnoreCase("jperez")).thenReturn(Optional.of(usuario));
        when(passwordEncoder.matches("Pica2026", "hash")).thenReturn(true);
        when(permisoService.permisosDe(42L)).thenReturn(Set.of("USUARIO_VER", "PERSONA_VER"));

        authService.login(new LoginRequest("jperez", "Pica2026"), null);

        verify(jwtService).generarAccessToken(42L, "jperez", List.of("ORGANIZADOR"),
                List.of("PERSONA_VER", "USUARIO_VER"));
    }

    @Test
    void cerrarSesionesYCambiarRolesRevocanLosRefreshDelUsuario() {
        authService.alInvalidarSesiones(new SesionesDeUsuarioInvalidadas(42L));
        authService.alCambiarRoles(new RolesDeUsuarioCambiados(7L));

        verify(refreshTokenRepository).revocarActivosPorUsuario(org.mockito.ArgumentMatchers.eq(42L), any(Instant.class));
        verify(refreshTokenRepository).revocarActivosPorUsuario(org.mockito.ArgumentMatchers.eq(7L), any(Instant.class));
    }

    private RefreshToken token(Instant expiraEn) {
        RefreshToken token = new RefreshToken();
        token.setUsuario(usuario);
        token.setTokenHash("hash");
        token.setExpiraEn(expiraEn);
        return token;
    }

    private static Rol rol(Long id, String nombre, EstadoGeneral estado) {
        Rol rol = new Rol();
        ReflectionTestUtils.setField(rol, "id", id);
        ReflectionTestUtils.setField(rol, "nombre", nombre);
        rol.setEstado(estado);
        return rol;
    }
}
