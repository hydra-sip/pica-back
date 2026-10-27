package com.hydra.pica.plataforma_pica.torneo.service;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.MotivoCierre;
import com.hydra.pica.plataforma_pica.inscripcion.service.CierreInscripcion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La inscripción de un torneo vista desde su organizador (HU-206): cerrarla a mano. Para cualquier otro usuario el
 * torneo no existe.
 */
@Service
@RequiredArgsConstructor
public class InscripcionTorneoService {

    private final TorneosPropios torneosPropios;
    private final TorneoRepository torneoRepository;
    private final CierreInscripcion cierreInscripcion;

    /** En cualquier momento mientras la inscripción está abierta, sin mínimo de inscriptos. */
    @Transactional
    public TorneoInformacion cerrar(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (torneo.getEstado() != EstadoTorneo.INSCRIPCION_ABIERTA) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA,
                    "El torneo " + id + " está " + torneo.getEstado() + ": solo se cierra con la inscripción abierta");
        }
        cierreInscripcion.cerrar(torneo, MotivoCierre.MANUAL, organizadorId);
        return torneosPropios.informacion(torneoRepository.saveAndFlush(torneo));
    }
}
