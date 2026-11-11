package com.hydra.pica.plataforma_pica.torneo.service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.MiDesignacion;
import com.hydra.pica.plataforma_pica.torneo.event.DesignacionRespondida;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que hace el árbitro con sus designaciones (HU-807, PICA-313): las ve y acepta o rechaza las pendientes. Hasta
 * que acepta no arbitra nada ({@link Arbitraje}); si rechaza, el organizador lo ve en el paso 2 y le llega un mail.
 */
@Service
@RequiredArgsConstructor
public class DesignacionesService {

    private static final Set<EstadoTorneo> TERMINADOS = Set.of(EstadoTorneo.FINALIZADO, EstadoTorneo.CANCELADO,
            // la cancelación ya está decidida: soporte solo revisa los gastos (HU-605)
            EstadoTorneo.CANCELACION_EN_REVISION);
    /** Primero las que esperan respuesta. */
    private static final Comparator<MiDesignacion> PENDIENTES_PRIMERO =
            Comparator.comparing(d -> d.estado() != EstadoDesignacion.PENDIENTE);

    private final ArbitroDesignadoRepository arbitroRepository;
    private final TorneoRepository torneoRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final ApplicationEventPublisher eventos;

    @Transactional(readOnly = true)
    public List<MiDesignacion> mias(Long usuarioId) {
        return arbitroRepository.delArbitro(usuarioId).stream()
                .map(MiDesignacion::de)
                .sorted(PENDIENTES_PRIMERO)
                .toList();
    }

    /** 409 COMPROMISO_NO_ACEPTADO sin el código de imparcialidad firmado. */
    @Transactional
    public MiDesignacion aceptar(Long usuarioId, Long torneoId, boolean compromisoAceptado) {
        ArbitroDesignado designacion = pendiente(usuarioId, torneoId);
        if (!compromisoAceptado) {
            throw new ConflictoException(CodigoError.COMPROMISO_NO_ACEPTADO,
                    "Hay que aceptar el código de imparcialidad para arbitrar el torneo " + torneoId);
        }
        if (!usuarioRolRepository.tieneRolActivo(usuarioId, ArbitrosTorneoService.ROL_ARBITRO)) {
            throw new ConflictoException(CodigoError.USUARIO_NO_ES_ARBITRO,
                    "El usuario " + usuarioId + " ya no tiene el rol de árbitro");
        }
        designacion.aceptar();
        return responder(designacion);
    }

    /** El motivo es opcional: en blanco queda null. */
    @Transactional
    public MiDesignacion rechazar(Long usuarioId, Long torneoId, String motivo) {
        ArbitroDesignado designacion = pendiente(usuarioId, torneoId);
        designacion.rechazar(motivo == null || motivo.isBlank() ? null : motivo.strip());
        return responder(designacion);
    }

    /**
     * Con el torneo bloqueado, como cuando el organizador cambia sus árbitros: así no se cruza con que lo quite.
     * 404 DESIGNACION_NO_ENCONTRADA si no lo designaron (o ya lo quitaron); 409 DESIGNACION_YA_RESPONDIDA si no
     * está PENDIENTE; 409 TORNEO_NO_EDITABLE si el torneo terminó.
     */
    private ArbitroDesignado pendiente(Long usuarioId, Long torneoId) {
        Torneo torneo = torneoRepository.buscarBloqueado(torneoId)
                .filter(t -> t.getEliminadoEn() == null)
                .orElseThrow(() -> noDesignado(usuarioId, torneoId));
        ArbitroDesignado designacion = arbitroRepository.findById(new ArbitroDesignadoId(torneoId, usuarioId))
                .orElseThrow(() -> noDesignado(usuarioId, torneoId));
        if (designacion.getEstado() != EstadoDesignacion.PENDIENTE) {
            throw new ConflictoException(CodigoError.DESIGNACION_YA_RESPONDIDA,
                    "La designación en el torneo " + torneoId + " ya está " + designacion.getEstado())
                    .con("estado", designacion.getEstado());
        }
        if (TERMINADOS.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + torneoId + " está " + torneo.getEstado());
        }
        return designacion;
    }

    private MiDesignacion responder(ArbitroDesignado designacion) {
        arbitroRepository.saveAndFlush(designacion);
        eventos.publishEvent(new DesignacionRespondida(designacion.getId().getTorneoId(),
                designacion.getId().getUsuarioId(), designacion.getEstado()));
        return MiDesignacion.de(designacion);
    }

    private static NoEncontradoException noDesignado(Long usuarioId, Long torneoId) {
        return new NoEncontradoException(CodigoError.DESIGNACION_NO_ENCONTRADA,
                "El usuario " + usuarioId + " no está designado en el torneo " + torneoId);
    }
}
