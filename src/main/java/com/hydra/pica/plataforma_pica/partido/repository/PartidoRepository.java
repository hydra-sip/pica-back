package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartidoRepository extends JpaRepository<Partido, Long> {

    /** Si tiene algún partido del torneo asignado, jugado o no: la FK no deja quitarlo (PICA-237). */
    boolean existsByTorneoIdAndArbitroId(Long torneoId, Long arbitroId);

    /** El bracket, con el árbitro y la cancha, por ronda y posición. */
    @Query("select p from Partido p join fetch p.arbitro a left join fetch a.persona left join fetch p.cancha "
            + "where p.torneo.id = :torneoId order by p.ronda, p.posicion")
    List<Partido> delTorneo(@Param("torneoId") Long torneoId);

    /** Para regenerar el bracket; antes hay que borrar los lados ({@link JuegaRepository#borrarDelTorneo}). */
    @Modifying(flushAutomatically = true)
    @Query("delete from Partido p where p.torneo.id = :torneoId")
    void borrarDelTorneo(@Param("torneoId") Long torneoId);
}
