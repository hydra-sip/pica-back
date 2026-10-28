package com.hydra.pica.plataforma_pica.torneo.service;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.equipo.repository.ConteoNomina;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.MotivoCierre;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDelTorneo;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.inscripcion.service.CierreInscripcion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoInformacion;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La inscripción de un torneo vista desde su organizador (HU-206): la lista de inscripciones y cerrarla a mano. Para
 * cualquier otro usuario el torneo no existe. También la cierra la tarea programada cuando vence la fecha
 * ({@link CierreAutomatico}).
 */
@Service
@RequiredArgsConstructor
public class InscripcionTorneoService {

    private final TorneosPropios torneosPropios;
    private final CierreInscripcion cierreInscripcion;
    private final InscripcionRepository inscripcionRepository;
    private final NominaRepository nominaRepository;
    private final TorneoRepository torneoRepository;

    /** Todas menos los borradores, de la más vieja a la más nueva; el orden no se elige desde afuera. */
    @Transactional(readOnly = true)
    public Page<InscripcionDelTorneo> listar(Long organizadorId, Long id, EstadoInscripcion estado, Pageable pageable) {
        torneosPropios.buscarPropio(organizadorId, id);
        Pageable enOrdenDeLlegada = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.ASC, "id"));
        Page<Inscripcion> pagina = inscripcionRepository.delTorneo(id, estado, enOrdenDeLlegada);
        Map<Long, Long> jugadores = nominaRepository.contarPorInscripcion(
                        pagina.map(Inscripcion::getId).getContent()).stream()
                .collect(Collectors.toMap(ConteoNomina::getInscripcionId, ConteoNomina::getCantidad));
        return pagina.map(i -> InscripcionDelTorneo.de(i, jugadores.getOrDefault(i.getId(), 0L).intValue()));
    }

    /** En cualquier momento mientras la inscripción está abierta, sin mínimo de inscriptos. */
    @Transactional
    public TorneoInformacion cerrar(Long organizadorId, Long id) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, id);
        if (torneo.getEstado() != EstadoTorneo.INSCRIPCION_ABIERTA) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_ABIERTA,
                    "El torneo " + id + " está " + torneo.getEstado() + ": solo se cierra con la inscripción abierta");
        }
        cierreInscripcion.cerrar(torneo, MotivoCierre.MANUAL, organizadorId);
        return torneosPropios.informacion(torneo);
    }

    /**
     * Cierra con el motivo FECHA si el torneo sigue con la inscripción abierta y la fecha vencida. Bloquea la fila y
     * vuelve a mirar: otra réplica, el organizador o el último pago pudieron cerrarla mientras tanto.
     *
     * @return si la cerró
     */
    @Transactional
    public boolean cerrarSiVencio(Long id, Instant ahora) {
        Torneo torneo = torneoRepository.buscarBloqueado(id).orElse(null);
        if (torneo == null || torneo.getEliminadoEn() != null || torneo.getEstado() != EstadoTorneo.INSCRIPCION_ABIERTA
                || torneo.getCierreInscripcionEn().isAfter(ahora)) {
            return false;
        }
        cierreInscripcion.cerrar(torneo, MotivoCierre.FECHA, null);
        return true;
    }
}
