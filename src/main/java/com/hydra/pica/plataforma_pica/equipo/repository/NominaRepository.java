package com.hydra.pica.plataforma_pica.equipo.repository;

import java.util.Collection;
import java.util.List;

import com.hydra.pica.plataforma_pica.equipo.domain.Nomina;
import com.hydra.pica.plataforma_pica.equipo.domain.NominaId;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NominaRepository extends JpaRepository<Nomina, NominaId> {

    List<Nomina> findAllByIdInscripcionId(Long inscripcionId);

    /** Cuántos hay en la nómina de cada una, en una sola consulta: para una página de inscripciones. */
    @Query("select n.id.inscripcionId as inscripcionId, count(n) as cantidad from Nomina n "
            + "where n.id.inscripcionId in :inscripcionIds group by n.id.inscripcionId")
    List<ConteoNomina> contarPorInscripcion(@Param("inscripcionIds") Collection<Long> inscripcionIds);

    boolean existsByIdInscripcionIdAndIdUsuarioId(Long inscripcionId, Long usuarioId);

    /** Los jugadores de la nómina de una inscripción, con su persona, en el orden en que se cargaron. */
    @Query("select u from Nomina n join n.usuario u join fetch u.persona where n.id.inscripcionId = :inscripcionId order by n.creadoEn, n.id.usuarioId")
    List<Usuario> jugadoresDe(@Param("inscripcionId") Long inscripcionId);

    /**
     * Si el jugador figura en la nómina de otra inscripción del torneo en alguno de esos estados.
     * {@code excluida}: la inscripción que se está editando (nula si es nueva), que no cuenta contra sí misma.
     */
    @Query("select count(n) > 0 from Nomina n, Inscripcion i where i.id = n.id.inscripcionId and i.torneo.id = :torneoId "
            + "and n.id.usuarioId = :usuarioId and i.estado in :estados and (:excluida is null or i.id <> :excluida)")
    boolean figuraEnOtraInscripcion(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId,
            @Param("estados") Collection<EstadoInscripcion> estados, @Param("excluida") Long excluida);
}
