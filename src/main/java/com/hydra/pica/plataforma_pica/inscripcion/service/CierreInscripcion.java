package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.MotivoCierre;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lo que pasa al cerrar la inscripción de un torneo (HU-206), lo cierre quien lo cierre: el organizador a mano, la
 * última confirmación del cupo o la tarea programada por la fecha. Corre dentro de la transacción de quien lo llama,
 * que ya tiene la fila del torneo bloqueada y chequeó que la inscripción esté abierta.
 */
@Component
@RequiredArgsConstructor
public class CierreInscripcion {

    private final InscripcionRepository inscripcionRepository;
    private final NominaRepository nominaRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final UsuarioRepository usuarioRepository;
    private final TorneoRepository torneoRepository;

    /** @param actorId el organizador que cierra a mano; null si se cierra sola, por cupo o por fecha */
    public void cerrar(Torneo torneo, MotivoCierre motivo, Long actorId) {
        torneo.setEstado(EstadoTorneo.INSCRIPCION_CERRADA);
        int canceladas = cancelarLasQueQuedanEnCurso(torneo);
        torneo.setListaHash(SelloTorneo.lista(lineas(torneo)));
        torneo.setListaSelladaEn(Instant.now());
        auditar(torneo, motivo, actorId, canceladas);
        torneoRepository.saveAndFlush(torneo);
    }

    // los borradores y las pendientes de pago no llegaron a pagar: no hay nada que devolver
    private int cancelarLasQueQuedanEnCurso(Torneo torneo) {
        List<Inscripcion> enCurso = inscripcionRepository.findByTorneoIdAndEstadoInOrderByIdAsc(torneo.getId(),
                List.of(EstadoInscripcion.BORRADOR, EstadoInscripcion.PENDIENTE_PAGO));
        enCurso.forEach(inscripcion -> inscripcion.setEstado(EstadoInscripcion.CANCELADA));
        return enCurso.size();
    }

    private List<String> lineas(Torneo torneo) {
        return inscripcionRepository.findByTorneoIdAndEstadoInOrderByIdAsc(torneo.getId(),
                        List.of(EstadoInscripcion.CONFIRMADA)).stream()
                .map(this::linea)
                .toList();
    }

    private String linea(Inscripcion inscripcion) {
        if (!inscripcion.esDeEquipo()) {
            return SelloTorneo.lineaIndividual(inscripcion.getId(), inscripcion.getUsuario().getId());
        }
        List<Long> nomina = nominaRepository.findAllByIdInscripcionId(inscripcion.getId()).stream()
                .map(n -> n.getId().getUsuarioId())
                .toList();
        return SelloTorneo.lineaDeEquipo(inscripcion.getId(), inscripcion.getEquipo().getId(), nomina);
    }

    private void auditar(Torneo torneo, MotivoCierre motivo, Long actorId, int canceladas) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("motivo", motivo.name());
        detalle.put("canceladas", canceladas);
        detalle.put("listaHash", torneo.getListaHash());
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(actorId == null ? null : usuarioRepository.getReferenceById(actorId));
        auditoria.setAccion(AccionAuditoria.CIERRE_INSCRIPCION);
        auditoria.setEntidad("TORNEO");
        auditoria.setEntidadId(String.valueOf(torneo.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
