package com.hydra.pica.plataforma_pica.partido.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.repository.AuditoriaRepository;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket;
import com.hydra.pica.plataforma_pica.partido.event.PaseDirecto;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra un partido con su ganador ya marcado y lo hace avanzar (HU-301, PICA-242). Es el paso común a aceptar el
 * resultado, vencer el plazo para objetarlo, el laudo (HU-304) y la ausencia (HU-309).
 *
 * <ul>
 * <li>El ganador pasa al partido de la ronda siguiente, en el lugar que le toca según el bracket
 * ({@link SorteoBracket#posicionSiguiente}, {@link SorteoBracket#ladoSiguiente}).</li>
 * <li>Si era la final, el torneo pasa a FINALIZADO y cada inscripción queda con su puesto: el campeón 1, el finalista
 * 2, y el que perdió en la ronda r de n, 2^(n - r) + 1. Sin partido por el tercer puesto, los dos semifinalistas
 * quedan 3.</li>
 * <li>Publica {@link ResultadoConfirmado} para los pronósticos y el tiempo real.</li>
 * <li>Si el partido hermano terminó sin ganador (HU-309), el ganador gana el partido siguiente sin jugar
 * ({@link #paseDirecto}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AvanceLlave {

    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final TorneoRepository torneoRepository;
    private final AuditoriaRepository auditoriaRepository;
    private final ApplicationEventPublisher eventos;

    /**
     * Dentro de la transacción de quien lo llama, con la fila del partido ya bloqueada.
     *
     * @param lados  los dos lados del partido, con {@code esGanador} ya puesto
     * @param estado CONFIRMADO, CORREGIDO o WALKOVER
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmar(Partido partido, List<Juega> lados, EstadoPartido estado) {
        Juega ganador = lados.stream()
                .filter(j -> Boolean.TRUE.equals(j.getEsGanador()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("El partido " + partido.getId() + " no tiene ganador"));
        partido.setEstado(estado);

        Torneo torneo = partido.getTorneo();
        int rondas = partidoRepository.rondasDelTorneo(torneo.getId());
        boolean esFinal = partido.getRonda() == rondas;
        Partido siguiente = null;
        Juega llega = null;
        if (esFinal) {
            finalizar(torneo, rondas);
        } else {
            siguiente = siguiente(partido);
            llega = juegaRepository.save(new Juega(siguiente, ganador.getInscripcion(),
                    SorteoBracket.ladoSiguiente(partido.getPosicion())));
        }
        eventos.publishEvent(new ResultadoConfirmado(torneo.getId(), partido.getId(),
                ganador.getId().getInscripcionId(), esFinal));
        if (!esFinal && terminoSinGanador(hermano(partido))) {
            paseDirecto(siguiente, llega);
        }
    }

    /**
     * El lado gana sin jugar el partido cuyo otro lugar quedó vacante porque el partido hermano terminó sin ganador
     * (HU-309): queda CONFIRMADO con un solo lado, como un BYE, y avanza. Queda en la auditoría como PASE_DIRECTO,
     * hecho por el sistema.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void paseDirecto(Partido partido, Juega lado) {
        Partido bloqueado = partidoRepository.buscarBloqueado(partido.getId()).orElseThrow();
        lado.setEsGanador(true);
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("resultado", "PASE_DIRECTO");
        detalle.put("inscripcionId", lado.getId().getInscripcionId());
        detalle.put("lado", lado.getLado().name());
        Auditoria auditoria = new Auditoria();
        auditoria.setAccion(AccionAuditoria.MODIFICACION);
        auditoria.setEntidad("PARTIDO");
        auditoria.setEntidadId(String.valueOf(bloqueado.getId()));
        auditoria.setDetalle(detalle);
        auditoriaRepository.save(auditoria);
        eventos.publishEvent(new PaseDirecto(bloqueado.getTorneo().getId(), bloqueado.getId(),
                lado.getId().getInscripcionId()));
        confirmar(bloqueado, List.of(lado), EstadoPartido.CONFIRMADO);
    }

    /** El partido de la ronda siguiente al que pasa el ganador. */
    Partido siguiente(Partido partido) {
        return partidoRepository.findByTorneoIdAndRondaAndPosicion(partido.getTorneo().getId(), partido.getRonda() + 1,
                SorteoBracket.posicionSiguiente(partido.getPosicion())).orElseThrow();
    }

    /** El otro partido de la misma ronda que alimenta al mismo partido de la ronda siguiente. */
    Partido hermano(Partido partido) {
        int posicion = partido.getPosicion() % 2 == 1 ? partido.getPosicion() + 1 : partido.getPosicion() - 1;
        return partidoRepository.findByTorneoIdAndRondaAndPosicion(partido.getTorneo().getId(), partido.getRonda(),
                posicion).orElseThrow();
    }

    /** WALKOVER con los dos lados perdedores: descalificaron a los dos (HU-309). */
    boolean terminoSinGanador(Partido partido) {
        return partido.getEstado() == EstadoPartido.WALKOVER && juegaRepository.delPartido(partido.getId()).stream()
                .noneMatch(j -> Boolean.TRUE.equals(j.getEsGanador()));
    }

    boolean esFinal(Partido partido) {
        return partido.getRonda() == partidoRepository.rondasDelTorneo(partido.getTorneo().getId());
    }

    private void finalizar(Torneo torneo, int rondas) {
        Torneo bloqueado = torneoRepository.buscarBloqueado(torneo.getId()).orElseThrow();
        bloqueado.setEstado(EstadoTorneo.FINALIZADO);
        for (Juega lado : juegaRepository.delTorneo(torneo.getId())) {
            int ronda = lado.getPartido().getRonda();
            if (Boolean.FALSE.equals(lado.getEsGanador())) {
                lado.getInscripcion().setPosicion((1 << (rondas - ronda)) + 1);
            } else if (Boolean.TRUE.equals(lado.getEsGanador()) && ronda == rondas) {
                lado.getInscripcion().setPosicion(1);
            }
        }
    }
}
