package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.Objecion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ObjecionRepository extends JpaRepository<Objecion, Long> {

    /** Las de todos los partidos del torneo, pendientes o laudadas, con su partido. */
    @Query("select o from Objecion o join fetch o.partido p where p.torneo.id = :torneoId")
    List<Objecion> delTorneo(@Param("torneoId") Long torneoId);
}
