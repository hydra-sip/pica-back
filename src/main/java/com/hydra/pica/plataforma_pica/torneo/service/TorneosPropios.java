package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
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
    private final EdicionTorneo edicionTorneo;
    private final ParametrosPlataforma parametrosPlataforma;

    Torneo buscarPropio(Long organizadorId, Long id) {
        return torneoRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(id, organizadorId)
                .orElseThrow(() -> noEncontrado(id));
    }

    /**
     * Con la fila bloqueada hasta el fin de la transacción, para que un cambio del asistente y la publicación no se
     * pisen (PICA-204). Se bloquea antes de leer: si no, el estado podría quedar viejo en memoria.
     */
    Torneo buscarEditable(Long organizadorId, Long id) {
        Torneo torneo = buscarBloqueado(organizadorId, id);
        if (torneo.getEstado() != EstadoTorneo.BORRADOR) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + id + " está " + torneo.getEstado() + ": solo se modifica en BORRADOR");
        }
        return torneo;
    }

    /** Propio y bloqueado como {@link #buscarEditable}, en cualquier estado: cada servicio decide cuáles admite. */
    Torneo buscarBloqueado(Long organizadorId, Long id) {
        return torneoRepository.buscarBloqueado(id)
                .filter(t -> t.getEliminadoEn() == null && t.getOrganizador().getId().equals(organizadorId))
                .orElseThrow(() -> noEncontrado(id));
    }

    /**
     * El torneo como lo ve su organizador, con los premios por puesto y lo que ya no se puede cambiar. Los gastos
     * administrativos son los sellados al publicar; en BORRADOR, los vigentes.
     */
    TorneoInformacion informacion(Torneo torneo) {
        BigDecimal pct = torneo.getPctGastosAdministrativos() != null ? torneo.getPctGastosAdministrativos()
                : parametrosPlataforma.gastosAdministrativos();
        return TorneoInformacion.de(torneo, premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId()),
                edicionTorneo.bloqueados(torneo), pct);
    }

    private static NoEncontradoException noEncontrado(Long id) {
        return new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "No existe el torneo " + id);
    }
}
