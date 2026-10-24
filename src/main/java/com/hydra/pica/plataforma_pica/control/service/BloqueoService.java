package com.hydra.pica.plataforma_pica.control.service;

import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.audit.AuditConstants;
import com.hydra.pica.plataforma_pica.control.domain.AlcanceBloqueo;
import com.hydra.pica.plataforma_pica.control.domain.Bloqueo;
import com.hydra.pica.plataforma_pica.control.repository.BloqueoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bloqueos de usuario con motivo y fecha de fin opcional (HU-703, CE3-3). El estado BLOQUEADO del
 * usuario sigue siendo lo que corta el login; la fila de {@code bloqueo} dice por qué y hasta cuándo.
 * Quien llama guarda el usuario y cierra sus sesiones.
 */
@Service
@RequiredArgsConstructor
public class BloqueoService {

    private final BloqueoRepository bloqueoRepository;
    private final AuditorAware<String> auditor;

    @Transactional(readOnly = true)
    public Optional<Bloqueo> vigente(Long usuarioId) {
        return bloqueoRepository.findFirstByUsuarioIdAndAlcanceAndLevantadoEnIsNullOrderByDesdeDesc(
                usuarioId, AlcanceBloqueo.USUARIO);
    }

    /** Registra el bloqueo y pasa el usuario a BLOQUEADO. Con {@code hasta} null es indefinido. */
    @Transactional
    public Bloqueo bloquear(Usuario usuario, String motivo, Instant hasta) {
        Bloqueo bloqueo = new Bloqueo();
        bloqueo.setUsuario(usuario);
        bloqueo.setAlcance(AlcanceBloqueo.USUARIO);
        bloqueo.setMotivo(motivo.strip());
        bloqueo.setHasta(hasta);
        usuario.setEstado(EstadoUsuario.BLOQUEADO);
        return bloqueoRepository.save(bloqueo);
    }

    /** Cierra los bloqueos abiertos y devuelve al usuario al estado que le toca por su email. */
    @Transactional
    public void levantar(Usuario usuario) {
        String por = auditor.getCurrentAuditor().orElse(AuditConstants.SISTEMA);
        bloqueoRepository.levantarAbiertos(usuario.getId(), AlcanceBloqueo.USUARIO, Instant.now(), por);
        usuario.setEstado(usuario.isEmailVerificado() ? EstadoUsuario.ACTIVO : EstadoUsuario.PENDIENTE_VERIFICACION);
    }

    /** Lo llama el login: si la fecha de fin ya pasó, levanta el bloqueo y devuelve true. */
    @Transactional
    public boolean levantarSiVencio(Usuario usuario) {
        Instant ahora = Instant.now();
        boolean vencido = vigente(usuario.getId())
                .map(Bloqueo::getHasta)
                .filter(hasta -> !hasta.isAfter(ahora))
                .isPresent();
        if (vencido) {
            levantar(usuario);
        }
        return vencido;
    }
}
