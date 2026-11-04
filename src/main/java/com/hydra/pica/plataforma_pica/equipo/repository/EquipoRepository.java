package com.hydra.pica.plataforma_pica.equipo.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipoRepository extends JpaRepository<Equipo, Long> {

    /** Los equipos del capitán que no están dados de baja. */
    Page<Equipo> findByCapitanIdAndEliminadoEnIsNull(Long capitanId, Pageable pageable);

    /** Un equipo del capitán que no está dado de baja. */
    Optional<Equipo> findByIdAndCapitanIdAndEliminadoEnIsNull(Long id, Long capitanId);
}
