package com.hydra.pica.plataforma_pica.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.RefreshToken;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.LoginRequest;
import com.hydra.pica.plataforma_pica.user.dto.TokenPair;
import com.hydra.pica.plataforma_pica.user.repository.RefreshTokenRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Duration REFRESH_TOKEN_DURATION = Duration.ofDays(7);
    private static final long ACCESS_TOKEN_EXPIRES_IN_SECONDS = 900L;

    private final UsuarioRepository usuarioRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public TokenPair login(LoginRequest request, String userAgent) {
        Usuario usuario = buscarPorIdentificador(request.identificador());
        if (usuario == null
                || usuario.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), usuario.getPasswordHash())) {
            throw credencialesInvalidas();
        }

        validarEstado(usuario);
        return emitirPar(usuario, userAgent);
    }

    @Transactional
    public TokenPair refresh(String refreshToken, String userAgent) {
        String hash = hash(refreshToken);
        RefreshToken actual = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> refreshException(CodigoError.REFRESH_INVALIDO, "El refresh token no es válido"));

        if (actual.getRevocadoEn() != null) {
            refreshTokenRepository.revocarActivosPorUsuario(actual.getUsuario().getId(), Instant.now());
            throw refreshException(CodigoError.REFRESH_REUTILIZADO,
                    "El refresh token ya fue utilizado y la sesión fue invalidada");
        }
        if (!actual.getExpiraEn().isAfter(Instant.now())) {
            throw refreshException(CodigoError.TOKEN_EXPIRADO, "El refresh token expiró");
        }

        TokenPair par = emitirPar(actual.getUsuario(), userAgent);
        actual.setRevocadoEn(Instant.now());
        actual.setReemplazadoPor(hash(par.refreshToken()));
        refreshTokenRepository.save(actual);
        return par;
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(refreshToken)).ifPresent(token -> {
            if (token.getRevocadoEn() == null) {
                token.setRevocadoEn(Instant.now());
                refreshTokenRepository.save(token);
            }
        });
    }

    private Usuario buscarPorIdentificador(String identificador) {
        if (identificador.contains("@")) {
            return usuarioRepository.findByEmailIgnoreCase(identificador).orElse(null);
        }
        return usuarioRepository.findByUsernameIgnoreCase(identificador).orElse(null);
    }

    private void validarEstado(Usuario usuario) {
        if (usuario.getEstado() == EstadoUsuario.BLOQUEADO) {
            throw new ApiException(HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO,
                    "El usuario está bloqueado");
        }
        if (usuario.getEstado() == EstadoUsuario.PENDIENTE_VERIFICACION || !usuario.isEmailVerificado()) {
            throw new ApiException(HttpStatus.FORBIDDEN, CodigoError.EMAIL_NO_VERIFICADO,
                    "El email del usuario no fue verificado");
        }
        if (usuario.getEstado() != EstadoUsuario.ACTIVO) {
            throw credencialesInvalidas();
        }
    }

    private TokenPair emitirPar(Usuario usuario, String userAgent) {
        List<String> roles = usuario.getRoles().stream()
                .map(asignacion -> asignacion.getRol().getNombre())
                .sorted()
                .toList();
        List<String> permisos = usuario.getRoles().stream()
                .flatMap(asignacion -> asignacion.getRol().getPermisos().stream())
                .map(permiso -> permiso.getCodigo())
                .distinct()
                .sorted()
                .toList();

        String accessToken = jwtService.generarAccessToken(usuario.getId(), usuario.getUsername(), roles, permisos);
        String refreshToken = UUID.randomUUID().toString();
        RefreshToken entidad = new RefreshToken();
        entidad.setUsuario(usuario);
        entidad.setTokenHash(hash(refreshToken));
        entidad.setExpiraEn(Instant.now().plus(REFRESH_TOKEN_DURATION));
        entidad.setCreadoEn(Instant.now());
        entidad.setUserAgent(userAgent);
        refreshTokenRepository.save(entidad);
        return new TokenPair(accessToken, refreshToken, "Bearer", ACCESS_TOKEN_EXPIRES_IN_SECONDS);
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static ApiException credencialesInvalidas() {
        return new ApiException(HttpStatus.UNAUTHORIZED, CodigoError.CREDENCIALES_INVALIDAS,
                "Las credenciales no son válidas");
    }

    private static ApiException refreshException(CodigoError codigo, String detalle) {
        return new ApiException(HttpStatus.UNAUTHORIZED, codigo, detalle);
    }
}
