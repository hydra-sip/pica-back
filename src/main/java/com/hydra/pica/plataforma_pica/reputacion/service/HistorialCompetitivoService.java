package com.hydra.pica.plataforma_pica.reputacion.service;

import com.hydra.pica.plataforma_pica.reputacion.dto.HistorialCompetitivo;
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
}
