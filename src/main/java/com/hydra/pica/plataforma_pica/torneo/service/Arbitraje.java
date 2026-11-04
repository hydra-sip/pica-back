package com.hydra.pica.plataforma_pica.torneo.service;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quién arbitra en un torneo (HU-203, PICA-237): un designado que aceptó la designación (HU-807), y solo con los
 * permisos que le dio el organizador. Lo usan los partidos: al asignarles árbitro y al cargar un resultado o
 * resolver una objeción.
 *
 * Se consulta la designación cada vez, así que al árbitro que se quita el acceso se le va en el momento.
 */
@Component
@RequiredArgsConstructor
public class Arbitraje {

    private final ArbitroDesignadoRepository arbitroRepository;

    @Transactional(readOnly = true)
    public boolean puedeArbitrar(Long torneoId, Long usuarioId, PermisoArbitro permiso) {
        return arbitroRepository.findById(new ArbitroDesignadoId(torneoId, usuarioId))
                .filter(a -> a.getEstado() == EstadoDesignacion.ACEPTADO)
                .map(ArbitroDesignado::getPermisos)
                .filter(permisos -> permisos.contains(permiso))
                .isPresent();
    }

    /** 403 SIN_PERMISO si no es un árbitro aceptado del torneo con ese permiso. */
    public void exigir(Long torneoId, Long usuarioId, PermisoArbitro permiso) {
        if (!puedeArbitrar(torneoId, usuarioId, permiso)) {
            throw new ProhibidoException(CodigoError.SIN_PERMISO,
                    "El usuario " + usuarioId + " no puede " + permiso + " en el torneo " + torneoId);
        }
    }

    /**
     * Para asignarle árbitro a un partido: la FK de partido ya exige que esté designado, y acá además que haya
     * aceptado. 409 ARBITRO_NO_HABILITADO si no.
     */
    @Transactional(readOnly = true)
    public void validarArbitroDePartido(Long torneoId, Long usuarioId) {
        boolean aceptado = arbitroRepository.findById(new ArbitroDesignadoId(torneoId, usuarioId))
                .filter(a -> a.getEstado() == EstadoDesignacion.ACEPTADO)
                .isPresent();
        if (!aceptado) {
            throw new ConflictoException(CodigoError.ARBITRO_NO_HABILITADO,
                    "El usuario " + usuarioId + " no es un árbitro aceptado del torneo " + torneoId);
        }
    }
}
