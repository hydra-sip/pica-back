package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;
import com.hydra.pica.plataforma_pica.partido.domain.GrupoInscripcion;
import com.hydra.pica.plataforma_pica.partido.domain.GrupoInscripcionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GrupoInscripcionRepository extends JpaRepository<GrupoInscripcion, GrupoInscripcionId> {

    /** Los integrantes de los grupos del torneo, con el nombre del equipo o del jugador. */
    @Query("select gi from GrupoInscripcion gi join fetch gi.grupo g join fetch gi.inscripcion i "
            + "left join fetch i.equipo left join fetch i.usuario u left join fetch u.persona "
            + "where gi.torneoId = :torneoId order by g.letra")
    List<GrupoInscripcion> delTorneo(@Param("torneoId") Long torneoId);

    /** Para descartar la llave (CR-01). */
    @Modifying(flushAutomatically = true)
    @Query("delete from GrupoInscripcion gi where gi.torneoId = :torneoId")
    void borrarDelTorneo(@Param("torneoId") Long torneoId);
}
