package com.hydra.pica.plataforma_pica.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.user.domain.CodigoCanjeOAuth;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.CodigoCanjeOAuthRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Almacena y valida códigos de canje temporales de OAuth (Google) persistidos en la base de datos (CE2-9).
 * Permite que cualquier réplica del backend genere el código y cualquier otra réplica lo canjee
 * sin requerir session affinity en el balanceador.
 */
@Component
public class OAuthCodeStore {

    private static final Duration CODE_TTL = Duration.ofSeconds(60);

    private final CodigoCanjeOAuthRepository codigoCanjeOAuthRepository;
    private final UsuarioRepository usuarioRepository;

    public OAuthCodeStore(
            CodigoCanjeOAuthRepository codigoCanjeOAuthRepository,
            UsuarioRepository usuarioRepository) {
        this.codigoCanjeOAuthRepository = codigoCanjeOAuthRepository;
        this.usuarioRepository = usuarioRepository;
    }

    @Transactional
    public String generarCodigo(Long usuarioId) {
        String code = UUID.randomUUID().toString();
        String hash = sha256Hex(code);

        Usuario usuario = usuarioRepository.getReferenceById(usuarioId);

        CodigoCanjeOAuth entidad = new CodigoCanjeOAuth();
        entidad.setCodigoHash(hash);
        entidad.setUsuario(usuario);
        entidad.setVenceEn(Instant.now().plus(CODE_TTL));
        entidad.setUsado(false);

        codigoCanjeOAuthRepository.save(entidad);
        return code;
    }

    @Transactional
    public Long consumirCodigo(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }

        String hash = sha256Hex(code);
        Optional<CodigoCanjeOAuth> opt = codigoCanjeOAuthRepository.findByCodigoHash(hash);
        if (opt.isEmpty()) {
            return null;
        }

        CodigoCanjeOAuth entidad = opt.get();
        if (entidad.isUsado() || entidad.getVenceEn().isBefore(Instant.now())) {
            return null;
        }

        entidad.setUsado(true);
        codigoCanjeOAuthRepository.save(entidad);

        return entidad.getUsuario().getId();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
