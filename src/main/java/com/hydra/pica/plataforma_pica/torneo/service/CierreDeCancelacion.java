package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.util.Set;

import com.hydra.pica.plataforma_pica.billetera.service.Reintegrador;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lo que pasa cuando una cancelación se resuelve (HU-605), sea automática o por la revisión de soporte: el torneo queda
 * CANCELADO, las inscripciones confirmadas REINTEGRADA, las que no terminaron de pagar CANCELADA, y la custodia se
 * reintegra. Corre dentro de la transacción de quien lo llama, con la fila del torneo bloqueada.
 */
@Component
@RequiredArgsConstructor
class CierreDeCancelacion {

    private final InscripcionRepository inscripcionRepository;
    private final TorneoRepository torneoRepository;
    private final Reintegrador reintegrador;

    /** @return lo que se devolvió a las billeteras */
    BigDecimal cerrar(Torneo torneo) {
        return cerrar(torneo, null);
    }

    /** Con la retención que aprobó soporte, o null para devolver todo. @return lo devuelto a las billeteras */
    BigDecimal cerrar(Torneo torneo, Reintegrador.Retencion retencion) {
        Long torneoId = torneo.getId();
        String nombre = torneo.getNombre();
        torneo.setEstado(EstadoTorneo.CANCELADO);
        for (Inscripcion inscripcion : inscripcionRepository.findByTorneoIdAndEstadoInOrderByIdAsc(torneoId,
                Set.of(EstadoInscripcion.BORRADOR, EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA))) {
            inscripcion.setEstado(inscripcion.getEstado() == EstadoInscripcion.CONFIRMADA
                    ? EstadoInscripcion.REINTEGRADA : EstadoInscripcion.CANCELADA);
        }
        torneoRepository.saveAndFlush(torneo);
        return reintegrador.reintegrarTorneo(torneoId, nombre, retencion);
    }
}
