package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.JuegaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JuegaRepository extends JpaRepository<Juega, JuegaId> {

    /** Los lados de todos los partidos del torneo, con el nombre del equipo o del jugador. */
    @Query("select j from Juega j join fetch j.inscripcion i left join fetch i.equipo "
            + "left join fetch i.usuario u left join fetch u.persona where j.partido.torneo.id = :torneoId")
    List<Juega> delTorneo(@Param("torneoId") Long torneoId);

    /** Los dos lados del partido (o uno, o ninguno), con el capitán del equipo para saber quién carga. */
    @Query("select j from Juega j join fetch j.inscripcion i left join fetch i.equipo e left join fetch e.capitan "
            + "left join fetch i.usuario u left join fetch u.persona where j.partido.id = :partidoId")
    List<Juega> delPartido(@Param("partidoId") Long partidoId);

    /** Si ya se cargó algún resultado en el torneo (un BYE no tiene puntaje). */
    boolean existsByPartidoTorneoIdAndPuntajeIsNotNull(Long torneoId);

    @Modifying(flushAutomatically = true)
    @Query("delete from Juega j where j.partido.id in (select p.id from Partido p where p.torneo.id = :torneoId)")
    void borrarDelTorneo(@Param("torneoId") Long torneoId);
}
