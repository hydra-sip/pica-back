package com.hydra.pica.plataforma_pica.partido.repository;

import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartidoRepository extends JpaRepository<Partido, Long> {

    /** Si tiene algún partido del torneo asignado, jugado o no: la FK no deja quitarlo (PICA-237). */
    boolean existsByTorneoIdAndArbitroId(Long torneoId, Long arbitroId);
}
