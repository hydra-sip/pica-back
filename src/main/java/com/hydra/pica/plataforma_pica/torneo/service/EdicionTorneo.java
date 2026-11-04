package com.hydra.pica.plataforma_pica.torneo.service;

import java.util.ArrayList;
import java.util.List;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Qué datos de los pasos 1 y 2 se pueden cambiar según el estado del torneo y sus inscripciones (HU-202, PICA-206).
 * Los campos se nombran como en {@code InformacionTorneoRequest}.
 * <ul>
 * <li>En BORRADOR, todos.</li>
 * <li>Publicado (inscripción abierta o cerrada): nunca la disciplina, si es individual o por equipos ni el reglamento,
 * que quedó sellado; con la inscripción cerrada, tampoco el cierre ni el cupo máximo (el cierre es definitivo,
 * HU-206). Con al menos una inscripción paga, además, nada
 * de sede, fechas, formato ni cupo mínimo: quedan el nombre, la descripción y el cupo máximo.</li>
 * <li>En curso, finalizado o cancelado, ninguno.</li>
 * </ul>
 * La parte económica no pasa por acá: sus endpoints solo la cambian en BORRADOR.
 */
@Component
@RequiredArgsConstructor
class EdicionTorneo {

    static final List<String> CAMPOS = List.of("nombre", "disciplina", "descripcion", "formato", "modalidad",
            "tipoParticipante", "sede", "canchas", "fechaInicio", "fechaFin", "cierreInscripcionEn", "reglamentoTexto",
            "cupoMaximo", "cupoMinimo", "tamanioEquipo", "nominaMaxima");

    private static final List<String> SELLADOS = List.of("disciplina", "tipoParticipante", "reglamentoTexto");

    private static final List<String> CON_INSCRIPCIONES_PAGAS = List.of("formato", "modalidad", "sede", "canchas",
            "fechaInicio", "fechaFin", "cierreInscripcionEn", "cupoMinimo", "tamanioEquipo", "nominaMaxima");

    private static final List<EstadoInscripcion> ACTIVAS =
            List.of(EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.CONFIRMADA);

    private final InscripcionRepository inscripcionRepository;

    static boolean editable(EstadoTorneo estado) {
        return estado == EstadoTorneo.BORRADOR || publicadoSinEmpezar(estado);
    }

    List<String> bloqueados(Torneo torneo) {
        EstadoTorneo estado = torneo.getEstado();
        if (estado == EstadoTorneo.BORRADOR) {
            return List.of();
        }
        if (!publicadoSinEmpezar(estado)) {
            return CAMPOS;
        }
        List<String> bloqueados = new ArrayList<>(SELLADOS);
        if (estado == EstadoTorneo.INSCRIPCION_CERRADA) {
            bloqueados.add("cierreInscripcionEn");
            bloqueados.add("cupoMaximo");
        }
        if (inscripcionRepository.countByTorneoIdAndEstado(torneo.getId(), EstadoInscripcion.CONFIRMADA) > 0) {
            CON_INSCRIPCIONES_PAGAS.stream().filter(c -> !bloqueados.contains(c)).forEach(bloqueados::add);
        }
        return CAMPOS.stream().filter(bloqueados::contains).toList();
    }

    /** Las que ocupan cupo: pendientes de pago y confirmadas. */
    long inscriptos(Long torneoId) {
        return ACTIVAS.stream().mapToLong(e -> inscripcionRepository.countByTorneoIdAndEstado(torneoId, e)).sum();
    }

    private static boolean publicadoSinEmpezar(EstadoTorneo estado) {
        return estado == EstadoTorneo.INSCRIPCION_ABIERTA || estado == EstadoTorneo.INSCRIPCION_CERRADA;
    }
}
