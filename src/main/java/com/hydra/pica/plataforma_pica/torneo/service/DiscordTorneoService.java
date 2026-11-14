package com.hydra.pica.plataforma_pica.torneo.service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.event.WebhookDiscordGuardado;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El webhook del canal de Discord del torneo (PICA-370), al que van los avisos (PICA-371). Lo guarda y lo quita solo
 * el organizador, desde el borrador hasta el torneo en curso. Al guardarlo, aunque sea el mismo, va un mensaje de
 * prueba al canal; si falla, el webhook queda guardado igual.
 */
@Service
@RequiredArgsConstructor
public class DiscordTorneoService {

    private static final Set<EstadoTorneo> CONFIGURABLE = EnumSet.of(EstadoTorneo.BORRADOR,
            EstadoTorneo.INSCRIPCION_ABIERTA, EstadoTorneo.INSCRIPCION_CERRADA, EstadoTorneo.EN_CURSO);

    private final TorneosPropios torneosPropios;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final ApplicationEventPublisher eventos;

    /** @param url ya validada contra {@code WebhookDiscord.PATRON} */
    @Transactional
    public TorneoInformacion guardar(Long organizadorId, Long id, String url) {
        Torneo torneo = configurable(organizadorId, id);
        torneo.setDiscordWebhook(url);
        auditar(organizadorId, id, "GUARDADO");
        eventos.publishEvent(new WebhookDiscordGuardado(id));
        return torneosPropios.informacion(torneo);
    }

    /** Sin webhook no hace nada. */
    @Transactional
    public TorneoInformacion quitar(Long organizadorId, Long id) {
        Torneo torneo = configurable(organizadorId, id);
        if (torneo.getDiscordWebhook() != null) {
            torneo.setDiscordWebhook(null);
            auditar(organizadorId, id, "QUITADO");
        }
        return torneosPropios.informacion(torneo);
    }

    /** Sin la URL: es un secreto. */
    private void auditar(Long organizadorId, Long id, String webhook) {
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(usuarioRepository.getReferenceById(organizadorId));
        auditoria.setAccion(AccionAuditoria.MODIFICACION);
        auditoria.setEntidad("TORNEO");
        auditoria.setEntidadId(String.valueOf(id));
        auditoria.setDetalle(Map.of("cambio", "DISCORD", "webhook", webhook));
        auditoriaRepository.save(auditoria);
    }

    private Torneo configurable(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (!CONFIGURABLE.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + id + " está " + torneo.getEstado() + ": el webhook de Discord se configura hasta"
                            + " que termina");
        }
        return torneo;
    }
}
