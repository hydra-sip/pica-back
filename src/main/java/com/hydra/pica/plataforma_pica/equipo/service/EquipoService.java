package com.hydra.pica.plataforma_pica.equipo.service;

import com.hydra.pica.plataforma_pica.equipo.dto.EquipoResumen;
import com.hydra.pica.plataforma_pica.equipo.repository.EquipoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Los equipos del usuario logueado (PICA-234). */
@Service
@RequiredArgsConstructor
public class EquipoService {

    private final EquipoRepository equipoRepository;

    /** Por nombre ascendente; el orden no se elige desde afuera. */
    @Transactional(readOnly = true)
    public Page<EquipoResumen> listarMios(Long capitanId, Pageable pageable) {
        Pageable porNombre = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, "nombre"));
        return equipoRepository.findByCapitanIdAndEliminadoEnIsNull(capitanId, porNombre).map(EquipoResumen::de);
    }
}
