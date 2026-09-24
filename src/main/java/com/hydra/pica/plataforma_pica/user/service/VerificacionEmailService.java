package com.hydra.pica.plataforma_pica.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.event.UsuarioCreado;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class VerificacionEmailService {

    private static final Duration DURACION_TOKEN = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(VerificacionEmailService.class);

    private final UsuarioRepository usuarioRepository;
    private final EmailService emailService;
    private final String baseUrl;

    public VerificacionEmailService(UsuarioRepository usuarioRepository,
                                    EmailService emailService,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${app.auth.verification-base-url:http://localhost:8080/api/v1/auth/verificar}")
                                    String baseUrl) {
        this.usuarioRepository = usuarioRepository;
        this.emailService = emailService;
        this.baseUrl = baseUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void alCrearUsuario(UsuarioCreado evento) {
        if (evento.requiereVerificacion()) {
            Usuario usuario = usuarioRepository.findById(evento.usuarioId()).orElse(null);
            if (usuario != null && usuario.getEstado() == EstadoUsuario.PENDIENTE_VERIFICACION) {
                String url = emitirToken(usuario);
                emailService.enviarVerificacion(usuario.getEmail(), url);
            }
        }
    }

    @Transactional
    public void verificar(String token) {
        Usuario usuario = usuarioRepository.findByTokenVerificacionHash(hash(token))
                .orElseThrow(() -> error(CodigoError.TOKEN_INVALIDO, "El token no es válido"));

        if (usuario.getEstado() == EstadoUsuario.ACTIVO) {
            throw error(CodigoError.TOKEN_USADO, "El token ya fue utilizado");
        }

        Instant ahora = Instant.now();
        if (usuario.getTokenVerificacionExpiraEn() == null
                || usuario.getTokenVerificacionExpiraEn().isBefore(ahora)) {
            throw error(CodigoError.TOKEN_EXPIRADO, "El token expiró");
        }
        if (usuario.getEstado() != EstadoUsuario.PENDIENTE_VERIFICACION) {
            throw error(CodigoError.TOKEN_USADO, "El token ya fue utilizado");
        }

        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuarioRepository.save(usuario);
    }

    @Transactional
    public void reenviar(String email) {
        usuarioRepository.findByEmailIgnoreCase(email).ifPresent(usuario -> {
            if (usuario.getEstado() == EstadoUsuario.PENDIENTE_VERIFICACION) {
                String url = emitirToken(usuario);
                try {
                    emailService.enviarVerificacion(usuario.getEmail(), url);
                } catch (MailException ex) {
                    log.warn("No se pudo reenviar el correo de verificación a {}", usuario.getEmail(), ex);
                } catch (RuntimeException ex) {
                    log.error("Falla inesperada al reenviar el correo de verificación a {}",
                            usuario.getEmail(), ex);
                }
            }
        });
    }

    public void validarPuedeIniciarSesion(Usuario usuario) {
        if (usuario.getEstado() == EstadoUsuario.PENDIENTE_VERIFICACION) {
            throw new ProhibidoException(CodigoError.EMAIL_NO_VERIFICADO,
                    "El correo electrónico todavía no fue verificado");
        }
        if (usuario.getEstado() == EstadoUsuario.BLOQUEADO) {
            throw new ProhibidoException(CodigoError.USUARIO_BLOQUEADO, "El usuario está bloqueado");
        }
    }

    private String emitirToken(Usuario usuario) {
        String token = UUID.randomUUID().toString();
        usuario.setTokenVerificacionHash(hash(token));
        usuario.setTokenVerificacionExpiraEn(Instant.now().plus(DURACION_TOKEN));
        usuarioRepository.saveAndFlush(usuario);
        return baseUrl + "?token=" + token;
    }

    private static String hash(String token) {
        if (token == null || token.isBlank()) {
            throw error(CodigoError.TOKEN_INVALIDO, "El token no es válido");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no está disponible", e);
        }
    }

    private static ApiException error(CodigoError codigo, String detalle) {
        return new ApiException(HttpStatus.BAD_REQUEST, codigo, detalle);
    }
}
