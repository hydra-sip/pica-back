package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;
import com.hydra.pica.plataforma_pica.partido.domain.Grupo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GrupoRepository extends JpaRepository<Grupo, Long> {

    List<Grupo> findByTorneoIdOrderByLetraAsc(Long torneoId);

    boolean existsByTorneoId(Long torneoId);

    /**
     * Para descartar la llave (CR-01); antes hay que borrar los partidos y los integrantes
     * ({@link GrupoInscripcionRepository#borrarDelTorneo}).
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from Grupo g where g.torneo.id = :torneoId")
    void borrarDelTorneo(@Param("torneoId") Long torneoId);
}
