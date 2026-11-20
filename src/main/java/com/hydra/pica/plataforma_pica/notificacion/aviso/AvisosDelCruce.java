package com.hydra.pica.plataforma_pica.notificacion.aviso;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.FasePartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket;
import com.hydra.pica.plataforma_pica.partido.event.CheckinHecho;
import com.hydra.pica.plataforma_pica.partido.event.LlaveSorteada;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.service.Partidos;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rival asignado e inicio del cruce (HU-808, T808-2), solo a los integrantes de los dos lados del partido.
 * <ul>
 * <li>Rival asignado: al sortear la llave ({@link LlaveSorteada}, también al pasar de grupos a la llave y al
 * re-sortear), a cada partido abierto que ya tiene sus dos lados; y cuando un ganador entra al partido siguiente
 * ({@link ResultadoConfirmado}, también el pase directo), si ese partido ya tiene a los dos.</li>
 * <li>Inicio del cruce: cuando el segundo check-in pasa el partido a EN_JUEGO ({@link CheckinHecho}).</li>
 * </ul>
 * Lee en una transacción propia y manda después, porque cada notificación se guarda en la suya.
 */
@Component
public class AvisosDelCruce {

    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final AvisosDePartido avisos;
    private final TransactionTemplate lectura;

    AvisosDelCruce(PartidoRepository partidoRepository, JuegaRepository juegaRepository, AvisosDePartido avisos,
                   PlatformTransactionManager transacciones) {
        this.partidoRepository = partidoRepository;
        this.juegaRepository = juegaRepository;
        this.avisos = avisos;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alSortearLlave(LlaveSorteada evento) {
        avisos.enviar(lectura.execute(estado -> rivalesDelTorneo(evento.torneoId())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alConfirmarResultado(ResultadoConfirmado evento) {
        if (!evento.torneoFinalizado()) {
            avisos.enviar(lectura.execute(estado -> rivalesDelSiguiente(evento.partidoId())));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alHacerCheckin(CheckinHecho evento) {
        avisos.enviar(lectura.execute(estado -> inicio(evento.partidoId())));
    }

    private List<Aviso> rivalesDelTorneo(Long torneoId) {
        List<Aviso> todos = new ArrayList<>();
        int rondas = partidoRepository.rondasDelTorneo(torneoId);
        for (Partido partido : partidoRepository.delTorneo(torneoId)) {
            if (!Partidos.CERRADOS.contains(partido.getEstado())) {
                todos.addAll(rivales(partido, rondas));
            }
        }
        return todos;
    }

    // un partido de grupos o de desempate no tiene siguiente: lo que viene después lo avisa LlaveSorteada
    private List<Aviso> rivalesDelSiguiente(Long partidoId) {
        Partido partido = partidoRepository.findById(partidoId).orElse(null);
        if (partido == null || partido.getFase() != FasePartido.ELIMINACION) {
            return List.of();
        }
        Long torneoId = partido.getTorneo().getId();
        return partidoRepository.findByTorneoIdAndRondaAndPosicion(torneoId, partido.getRonda() + 1,
                        SorteoBracket.posicionSiguiente(partido.getPosicion()))
                .filter(siguiente -> !Partidos.CERRADOS.contains(siguiente.getEstado()))
                .map(siguiente -> rivales(siguiente, partidoRepository.rondasDelTorneo(torneoId)))
                .orElse(List.of());
    }

    private List<Aviso> rivales(Partido partido, int rondas) {
        List<Juega> lados = juegaRepository.delPartido(partido.getId());
        if (lados.size() != 2) {
            return List.of();
        }
        String ronda = Partidos.nombreRonda(partido, rondas);
        String torneo = partido.getTorneo().getNombre();
        String cuando = partido.getFechaProgramada() == null
                ? "La fecha y la hora las define el organizador."
                : "Se juega el " + AvisosDePartido.diaYHora(partido.getFechaProgramada()) + ".";
        return avisos.aLosDosLados(partido, lados, TipoNotificacion.RIVAL_ASIGNADO,
                rival -> "Tu rival en " + ronda + ": " + rival,
                rival -> "En " + ronda + " de " + torneo + " jugás contra " + rival + ". " + cuando,
                Map.of("ronda", ronda));
    }

    private List<Aviso> inicio(Long partidoId) {
        Partido partido = partidoRepository.findById(partidoId).orElse(null);
        if (partido == null || partido.getEstado() != EstadoPartido.EN_JUEGO) {
            return List.of();
        }
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        String ronda = Partidos.nombreRonda(partido, partidoRepository.rondasDelTorneo(partido.getTorneo().getId()));
        String torneo = partido.getTorneo().getNombre();
        return avisos.aLosDosLados(partido, lados, TipoNotificacion.CRUCE_INICIADO,
                rival -> "Empezó tu partido contra " + rival,
                rival -> "Los dos lados hicieron check-in: empezó " + ronda + " de " + torneo + " contra " + rival
                        + ". Al terminar, el capitán carga el resultado.",
                Map.of("ronda", ronda));
    }
}
