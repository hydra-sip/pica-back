package com.hydra.pica.plataforma_pica.torneo.service;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lo que comparten los servicios del asistente (pasos 1 y 2, y la parte económica): cada organizador ve y edita solo
 * sus torneos, el de otro responde 404 como si no existiera, y los pasos del asistente se guardan solo en BORRADOR.
 * Corre dentro de la transacción de quien lo llama.
 */
@Component
@RequiredArgsConstructor
class TorneosPropios {

    private final TorneoRepository torneoRepository;
    private final PremioRepository premioRepository;

    Torneo buscarPropio(Long organizadorId, Long id) {
        return torneoRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(id, organizadorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + id));
    }

    Torneo buscarEditable(Long organizadorId, Long id) {
        Torneo torneo = buscarPropio(organizadorId, id);
        if (torneo.getEstado() != EstadoTorneo.BORRADOR) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + id + " está " + torneo.getEstado() + ": solo se modifica en BORRADOR");
        }
        return torneo;
    }

    /** El torneo como lo ve su organizador, con los premios por puesto. */
    TorneoInformacion informacion(Torneo torneo) {
        return TorneoInformacion.de(torneo, premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId()));
    }
}
