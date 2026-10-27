package com.hydra.pica.plataforma_pica.partido.service;

import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.domain.SorteoBracket;
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
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AvanceLlave {

    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final TorneoRepository torneoRepository;
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
        if (esFinal) {
            finalizar(torneo, rondas);
        } else {
            Partido siguiente = partidoRepository.findByTorneoIdAndRondaAndPosicion(torneo.getId(),
                    partido.getRonda() + 1, SorteoBracket.posicionSiguiente(partido.getPosicion())).orElseThrow();
            juegaRepository.save(new Juega(siguiente, ganador.getInscripcion(),
                    SorteoBracket.ladoSiguiente(partido.getPosicion())));
        }
        eventos.publishEvent(new ResultadoConfirmado(torneo.getId(), partido.getId(),
                ganador.getId().getInscripcionId(), esFinal));
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
