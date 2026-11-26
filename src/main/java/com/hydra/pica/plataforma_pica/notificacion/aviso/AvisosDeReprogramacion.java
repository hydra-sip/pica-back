package com.hydra.pica.plataforma_pica.notificacion.aviso;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.torneo.domain.MinimoInscriptos;
import com.hydra.pica.plataforma_pica.torneo.domain.Reprogramacion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.event.PlazoReprogramacionVencido;
import com.hydra.pica.plataforma_pica.torneo.event.ReprogramacionPorVencer;
import com.hydra.pica.plataforma_pica.torneo.event.ReprogramacionRechazada;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoReprogramado;
import com.hydra.pica.plataforma_pica.torneo.repository.ReprogramacionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los avisos de la reprogramación de un torneo (CR-01-4, PICA-393), por el centro de notificaciones: quedan guardados,
 * llegan por WebSocket y por correo.
 * <ul>
 * <li>Al reprogramar ({@link TorneoReprogramado}), a cada integrante de las inscripciones confirmadas: la fecha de
 * antes, la nueva, el motivo y hasta cuándo pueden bajarse.</li>
 * <li>Un día antes de que venza el plazo ({@link ReprogramacionPorVencer}), el recordatorio a los que siguen
 * inscriptos.</li>
 * <li>Si con una baja no llega al mínimo para jugar ({@link ReprogramacionRechazada}), al organizador: el torneo no se
 * cancela solo, decide él.</li>
 * <li>Al vencer el plazo ({@link PlazoReprogramacionVencido}), al organizador: cuántos se bajaron y cómo quedó la
 * inscripción.</li>
 * </ul>
 * Lee en una transacción propia y manda después, porque cada notificación se guarda en la suya; si el correo falla, la
 * reprogramación queda hecha igual.
 */
@Component
public class AvisosDeReprogramacion {

    private final ReprogramacionRepository reprogramacionRepository;
    private final InscripcionRepository inscripcionRepository;
    private final AvisosDePartido avisos;
    private final TransactionTemplate lectura;

    AvisosDeReprogramacion(ReprogramacionRepository reprogramacionRepository,
                           InscripcionRepository inscripcionRepository, AvisosDePartido avisos,
                           PlatformTransactionManager transacciones) {
        this.reprogramacionRepository = reprogramacionRepository;
        this.inscripcionRepository = inscripcionRepository;
        this.avisos = avisos;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alReprogramar(TorneoReprogramado evento) {
        avisos.enviar(lectura.execute(estado -> nuevaFecha(evento.reprogramacionId())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alFaltarPoco(ReprogramacionPorVencer evento) {
        avisos.enviar(lectura.execute(estado -> recordatorio(evento.reprogramacionId())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alRechazar(ReprogramacionRechazada evento) {
        if (evento.bajoElMinimo()) {
            avisos.enviar(lectura.execute(estado -> bajoElMinimo(evento.reprogramacionId())));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alVencerElPlazo(PlazoReprogramacionVencido evento) {
        avisos.enviar(lectura.execute(estado -> resuelta(evento.reprogramacionId())));
    }

    private List<Aviso> nuevaFecha(Long reprogramacionId) {
        Reprogramacion r = reprogramacionRepository.findById(reprogramacionId).orElseThrow();
        Torneo torneo = r.getTorneo();
        String cuerpo = ("%s pasa del %s al %s. Motivo: %s\n\nSi la nueva fecha no te sirve, quien hizo la inscripción "
                + "(vos, o el capitán del equipo) puede bajarse desde PICA hasta el %s y se le devuelve la inscripción; "
                + "los gastos administrativos no se devuelven. Si no hacés nada, seguís inscripto.")
                .formatted(torneo.getNombre(), AvisosDePartido.diaYHora(r.getFechaInicioAnterior()),
                        AvisosDePartido.diaYHora(r.getFechaInicioNueva()), r.getMotivo(),
                        AvisosDePartido.diaYHora(r.getRechazoHasta()));
        return aLosInscriptos(r, TipoNotificacion.TORNEO_REPROGRAMADO, "Cambió la fecha de " + torneo.getNombre(),
                cuerpo);
    }

    private List<Aviso> recordatorio(Long reprogramacionId) {
        Reprogramacion r = reprogramacionRepository.findById(reprogramacionId).orElseThrow();
        Torneo torneo = r.getTorneo();
        String cuerpo = ("El plazo para bajarte de %s vence el %s. Si la nueva fecha (%s) no te sirve, quien hizo la "
                + "inscripción puede bajarse desde PICA hasta entonces. Si no hacés nada, seguís inscripto.")
                .formatted(torneo.getNombre(), AvisosDePartido.diaYHora(r.getRechazoHasta()),
                        AvisosDePartido.diaYHora(r.getFechaInicioNueva()));
        return aLosInscriptos(r, TipoNotificacion.REPROGRAMACION_POR_VENCER,
                "Último día para bajarte de " + torneo.getNombre(), cuerpo);
    }

    private List<Aviso> bajoElMinimo(Long reprogramacionId) {
        Reprogramacion r = reprogramacionRepository.findById(reprogramacionId).orElseThrow();
        Torneo torneo = r.getTorneo();
        long confirmadas = confirmadas(torneo);
        String cuerpo = ("Con las bajas por la nueva fecha, %s quedó por debajo del mínimo para jugar: inscripciones "
                + "confirmadas, %d; hacen falta %d. El torneo no se cancela solo: cuando venza el plazo (%s) la "
                + "inscripción se abre hasta el %s para que se anote más gente, o podés cancelarlo.")
                .formatted(torneo.getNombre(), confirmadas, MinimoInscriptos.de(torneo),
                        AvisosDePartido.diaYHora(r.getRechazoHasta()),
                        AvisosDePartido.diaYHora(r.getCierreInscripcionNuevo()));
        return List.of(alOrganizador(r, TipoNotificacion.INSCRIPTOS_BAJO_EL_MINIMO,
                torneo.getNombre() + " quedó por debajo del mínimo", cuerpo));
    }

    private List<Aviso> resuelta(Long reprogramacionId) {
        Reprogramacion r = reprogramacionRepository.findById(reprogramacionId).orElseThrow();
        Torneo torneo = r.getTorneo();
        String bajas = switch (r.getRechazos()) {
            case 0 -> "Nadie se bajó por la nueva fecha.";
            case 1 -> "Se bajó 1 inscripción por la nueva fecha.";
            default -> "Se bajaron " + r.getRechazos() + " inscripciones por la nueva fecha.";
        };
        String inscripcion = switch (torneo.getEstado()) {
            case INSCRIPCION_ABIERTA -> " La inscripción está abierta hasta el %s para ocupar los lugares libres."
                    .formatted(AvisosDePartido.diaYHora(torneo.getCierreInscripcionEn()));
            case INSCRIPCION_CERRADA -> " El cupo está completo: la inscripción quedó cerrada y ya podés generar la "
                    + "llave.";
            default -> "";
        };
        String cuerpo = "Venció el plazo para rechazar la nueva fecha de %s. %s Inscripciones confirmadas: %d.%s"
                .formatted(torneo.getNombre(), bajas, confirmadas(torneo), inscripcion);
        return List.of(alOrganizador(r, TipoNotificacion.REPROGRAMACION_RESUELTA,
                "Terminó el plazo de la nueva fecha de " + torneo.getNombre(), cuerpo));
    }

    /** A cada integrante de las inscripciones confirmadas: las que se bajaron ya no están. */
    private List<Aviso> aLosInscriptos(Reprogramacion r, TipoNotificacion tipo, String titulo, String cuerpo) {
        Torneo torneo = r.getTorneo();
        List<Aviso> todos = new ArrayList<>();
        for (Inscripcion inscripcion : inscripcionRepository.findByTorneoIdAndEstadoInOrderByIdAsc(torneo.getId(),
                List.of(EstadoInscripcion.CONFIRMADA))) {
            for (Long usuarioId : avisos.integrantes(inscripcion)) {
                todos.add(new Aviso(usuarioId, tipo, titulo, cuerpo, enlace(torneo), datos(r)));
            }
        }
        return todos;
    }

    private Aviso alOrganizador(Reprogramacion r, TipoNotificacion tipo, String titulo, String cuerpo) {
        Torneo torneo = r.getTorneo();
        return new Aviso(torneo.getOrganizador().getId(), tipo, titulo, cuerpo, enlace(torneo), datos(r));
    }

    private long confirmadas(Torneo torneo) {
        return inscripcionRepository.countByTorneoIdAndEstado(torneo.getId(), EstadoInscripcion.CONFIRMADA);
    }

    private static String enlace(Torneo torneo) {
        return torneo.getSlug() == null ? null : "/torneos/" + torneo.getSlug();
    }

    private static Map<String, Object> datos(Reprogramacion r) {
        return Map.of("torneoId", r.getTorneo().getId(), "reprogramacionId", r.getId(),
                "rechazoHasta", r.getRechazoHasta().toString());
    }
}
