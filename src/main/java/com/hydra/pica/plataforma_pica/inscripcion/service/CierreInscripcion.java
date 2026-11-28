package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.domain.EntidadAuditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.equipo.domain.Nomina;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.MotivoCierre;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoGrupos;
import com.hydra.pica.plataforma_pica.rifa.service.Drand;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
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
    private final Drand drand;
    private final Clock clock;

    /** @param actorId el organizador que cierra a mano; null si se cierra sola, por cupo o por fecha */
    public void cerrar(Torneo torneo, MotivoCierre motivo, Long actorId) {
        torneo.setEstado(EstadoTorneo.INSCRIPCION_CERRADA);
        int canceladas = cancelarLasQueQuedanEnCurso(torneo);
        torneo.setListaHash(SelloTorneo.lista(lineas(torneo)));
        torneo.setListaSelladaEn(Instant.now(clock));
        if (torneo.getFormato() == Formato.GRUPOS_Y_ELIMINACION) {
            comprometerSorteoDeGrupos(torneo);
        }
        auditar(torneo, motivo, actorId, canceladas);
        torneoRepository.saveAndFlush(torneo);
    }

    /**
     * Con grupos (CR-02, PICA-397): la semilla del sorteo de grupos va a ser el valor de la próxima ronda de drand, que
     * todavía no salió. Se publica el compromiso con la lista ya sellada, así nadie (ni el organizador ni la
     * plataforma) elige la semilla.
     */
    private void comprometerSorteoDeGrupos(Torneo torneo) {
        long ronda = drand.ronda(Instant.now(clock)) + 1;
        torneo.setSemillaRonda(ronda);
        torneo.setCompromisoHash(SorteoGrupos.compromiso(torneo.getListaHash(), ronda));
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
        List<Nomina> nomina = nominaRepository.findAllByIdInscripcionId(inscripcion.getId());
        Long equipoId = inscripcion.getEquipo().getId();
        // sin reparto (CR-05) la línea conserva la forma de antes, así los sellos ya fijados se recalculan igual
        if (nomina.stream().anyMatch(n -> n.getPctPremio() == null)) {
            return SelloTorneo.lineaDeEquipo(inscripcion.getId(), equipoId,
                    nomina.stream().map(n -> n.getId().getUsuarioId()).toList());
        }
        Map<Long, BigDecimal> reparto = new HashMap<>();
        nomina.forEach(n -> reparto.put(n.getId().getUsuarioId(), n.getPctPremio()));
        return SelloTorneo.lineaDeEquipoConReparto(inscripcion.getId(), equipoId, reparto);
    }

    private void auditar(Torneo torneo, MotivoCierre motivo, Long actorId, int canceladas) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("motivo", motivo.name());
        detalle.put("canceladas", canceladas);
        detalle.put("listaHash", torneo.getListaHash());
        if (torneo.getCompromisoHash() != null) {
            detalle.put("semillaRonda", torneo.getSemillaRonda());
            detalle.put("compromisoHash", torneo.getCompromisoHash());
        }
        Auditoria auditoria = new Auditoria();
        auditoria.setUsuario(actorId == null ? null : usuarioRepository.getReferenceById(actorId));
        auditoria.setAccion(AccionAuditoria.CIERRE_INSCRIPCION);
        auditoria.setEntidad(EntidadAuditoria.TORNEO.name());
        auditoria.setEntidadId(String.valueOf(torneo.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
    }
}
