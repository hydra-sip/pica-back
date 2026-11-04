package com.hydra.pica.plataforma_pica.user.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hydra.pica.plataforma_pica.common.config.IntentosLoginConfig.IntentosLoginProperties;
import com.hydra.pica.plataforma_pica.common.error.DemasiadosIntentosException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los logins en la auditoría (LOGIN y LOGIN_FALLIDO, V9) y el límite de fallidos (RNF-SEG-06).
 *
 * <ul>
 * <li>Cuenta por identificador escrito (usuario o email, en minúsculas), exista o no: así el 429 no revela qué
 * usuarios hay. No cuenta por IP, porque en la facultad todos salen por la misma.</li>
 * <li>Solo cuentan los fallidos de la ventana posteriores al último login correcto del usuario.</li>
 * <li>El fallido se guarda en su propia transacción: el 401 hace rollback de la del login.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class IntentosLogin {

    private final AuditoriaRepository auditoriaRepository;
    private final IntentosLoginProperties propiedades;

    /** 429 DEMASIADOS_INTENTOS si ya se llegó al máximo de fallidos en la ventana, aunque la clave sea la correcta. */
    @Transactional(readOnly = true)
    public void exigirDisponible(String identificador, Usuario usuario) {
        Instant ahora = Instant.now();
        Instant desde = ahora.minus(propiedades.ventana());
        if (usuario != null) {
            Instant ultimoLogin = auditoriaRepository.ultimoLogin(usuario.getId());
            if (ultimoLogin != null && ultimoLogin.isAfter(desde)) {
                desde = ultimoLogin;
            }
        }
        List<Instant> fallidos = auditoriaRepository.loginsFallidosDesde(normalizar(identificador), desde);
        if (fallidos.size() >= propiedades.maximo()) {
            // se puede volver a intentar cuando el que deja la cuenta en el máximo sale de la ventana
            Instant libre = fallidos.get(fallidos.size() - propiedades.maximo()).plus(propiedades.ventana());
            long segundos = Math.max(1, Duration.between(ahora, libre).toSeconds());
            throw new DemasiadosIntentosException(segundos,
                    "Demasiados intentos fallidos: se puede volver a intentar en " + segundos + " segundos");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrarFallido(String identificador, Usuario usuario, String ip) {
        registrar(AccionAuditoria.LOGIN_FALLIDO, identificador, usuario, ip);
    }

    /** En la transacción del login: si no se emite la sesión, tampoco queda el registro. */
    public void registrarLogin(String identificador, Usuario usuario, String ip) {
        registrar(AccionAuditoria.LOGIN, identificador, usuario, ip);
    }

    private void registrar(AccionAuditoria accion, String identificador, Usuario usuario, String ip) {
        Auditoria auditoria = new Auditoria();
        auditoria.setAccion(accion);
        auditoria.setUsuario(usuario);
        auditoria.setEntidad("USUARIO");
        auditoria.setEntidadId(usuario == null ? null : String.valueOf(usuario.getId()));
        auditoria.setIp(ip);
        auditoria.setDetalle(Map.of("identificador", normalizar(identificador)));
        auditoriaRepository.save(auditoria);
    }

    private static String normalizar(String identificador) {
        return identificador.strip().toLowerCase(Locale.ROOT);
    }
}
