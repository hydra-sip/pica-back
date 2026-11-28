package com.hydra.pica.plataforma_pica.reputacion.service;

import java.util.List;
import com.hydra.pica.plataforma_pica.reputacion.dto.HistorialCompetitivo;
import com.hydra.pica.plataforma_pica.reputacion.dto.PerfilJugador.TorneoJugado;
import com.hydra.pica.plataforma_pica.reputacion.repository.HistorialCompetitivoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** El historial competitivo de un usuario (HU-801, T801-1). Se calcula en el momento; no guarda contadores. */
@Service
@RequiredArgsConstructor
public class HistorialCompetitivoService {

    private final HistorialCompetitivoRepository historialRepository;

    @Transactional(readOnly = true)
    public HistorialCompetitivo de(Long usuarioId) {
        HistorialCompetitivoRepository.Historial historial = historialRepository.historial(usuarioId);
        return new HistorialCompetitivo(historial.getTorneosJugados(), historial.getVictorias(),
                historial.getDerrotas(), historial.getPodios());
    }

    /** Los últimos 5 torneos que terminó, el más reciente primero: los mismos que cuenta {@code torneosJugados}. */
    @Transactional(readOnly = true)
    public List<TorneoJugado> ultimosTorneos(Long usuarioId) {
        return historialRepository.ultimosTorneos(usuarioId).stream()
                .map(t -> new TorneoJugado(t.getTorneoId(), t.getSlug(), t.getNombre(), t.getDisciplina(),
                        t.getFechaFin(), t.getPosicion(), t.getEquipo()))
                .toList();
    }
}
