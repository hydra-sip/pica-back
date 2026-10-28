package com.hydra.pica.plataforma_pica.partido.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.CheckinPartido.EstadoVentana;
import com.hydra.pica.plataforma_pica.partido.event.PartidoSinPresentes;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.CriterioSinPresentes;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La ausencia al vencer la ventana de check-in (HU-309, PICA-252). Si hizo check-in un solo lado, gana por ausencia
 * (walkover): el partido queda WALKOVER, sin puntajes ni plazo para objetarlo, y avanza la llave ({@link AvanceLlave}).
 * Si no se presentó ninguno, se aplica el criterio del torneo ({@link CriterioSinPresentes}) y se publica
 * {@link PartidoSinPresentes}.
 */
@Service
@RequiredArgsConstructor
public class AusenciaService {

    private final Partidos partidos;
    private final JuegaRepository juegaRepository;
    private final VentanasCheckin ventanas;
    private final AvanceLlave avanceLlave;
    private final ResultadoService resultadoService;
    private final ApplicationEventPublisher eventos;

    /**
     * Lo llama {@link AusenciaAutomatica} por cada partido con la ventana vencida. Vuelve a mirar con la fila
     * bloqueada: entre la consulta y acá pudieron haber cargado el resultado u otra réplica pudo haberlo resuelto.
     *
     * @return si lo resolvió
     */
    @Transactional
    public boolean declararSiVencio(Long partidoId, Instant ahora) {
        Partido partido = partidos.buscarBloqueado(partidoId);
        List<Juega> lados = juegaRepository.delPartido(partidoId);
        if (partido.getTorneo().getEstado() != EstadoTorneo.EN_CURSO || lados.size() < 2
                || (partido.getEstado() != EstadoPartido.PROGRAMADO && partido.getEstado() != EstadoPartido.CHECK_IN)
                || ventanas.estado(VentanasCheckin.de(partido), ahora) != EstadoVentana.CERRADA) {
            return false;
        }
        List<Juega> presentes = lados.stream().filter(j -> j.getCheckinEn() != null).toList();
        if (presentes.isEmpty()) {
            return sinPresentes(partido);
        }
        if (presentes.size() != 1) {
            return false;
        }
        Juega presente = presentes.getFirst();
        Juega ausente = lados.stream().filter(j -> j != presente).findFirst().orElseThrow();
        presente.setEsGanador(true);
        ausente.setEsGanador(false);
        avanceLlave.confirmar(partido, lados, EstadoPartido.WALKOVER);
        resultadoService.auditar(partido, null, ResultadoService.detalle("WALKOVER", ausente));
        return true;
    }

    /** Ninguno hizo check-in: con REPROGRAMAR el partido sigue PROGRAMADO, sin fecha y por lo tanto sin ventana. */
    private boolean sinPresentes(Partido partido) {
        CriterioSinPresentes criterio = partido.getTorneo().getCheckinSinPresentes();
        if (criterio != CriterioSinPresentes.REPROGRAMAR) {
            return false;
        }
        partido.setFechaProgramada(null);
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("resultado", "SIN_PRESENTES");
        detalle.put("criterio", criterio.name());
        resultadoService.auditar(partido, null, detalle);
        eventos.publishEvent(new PartidoSinPresentes(partido.getTorneo().getId(), partido.getId()));
        return true;
    }
}
