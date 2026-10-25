package com.hydra.pica.plataforma_pica.equipo.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipoRepository extends JpaRepository<Equipo, Long> {

    /** Un equipo del capitán que no está dado de baja. */
    Optional<Equipo> findByIdAndCapitanIdAndEliminadoEnIsNull(Long id, Long capitanId);
}
