package com.hydra.pica.plataforma_pica.partido.service;

import java.util.List;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra un partido de la fase de grupos (CR-02, PICA-398), el equivalente de {@link AvanceLlave} para los grupos:
 * nadie avanza, el resultado suma a la tabla. Puede ser empate (los dos lados sin ganador).
 */
@Component
@RequiredArgsConstructor
public class FaseDeGrupos {

    private final ApplicationEventPublisher eventos;

    /**
     * Dentro de la transacción de quien lo llama, con la fila del partido ya bloqueada.
     *
     * @param estado CONFIRMADO, CORREGIDO o WALKOVER
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmar(Partido partido, List<Juega> lados, EstadoPartido estado) {
        partido.setEstado(estado);
        Long ganador = lados.stream()
                .filter(j -> Boolean.TRUE.equals(j.getEsGanador()))
                .map(j -> j.getId().getInscripcionId())
                .findFirst()
                .orElse(null);
        eventos.publishEvent(new ResultadoConfirmado(partido.getTorneo().getId(), partido.getId(), ganador, false));
        terminado(partido);
    }

    /** Un partido de grupos que ya no se juega: confirmado, por ausencia o sin presentes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void terminado(Partido partido) {
        // CR-02-5 y CR-02-6: con el grupo terminado, los desempates y el pase a la llave
    }
}
