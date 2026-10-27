package com.hydra.pica.plataforma_pica.inscripcion.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InscripcionRepository extends JpaRepository<Inscripcion, Long> {

    boolean existsByTorneoIdAndUsuarioIdAndEstadoIn(Long torneoId, Long usuarioId, Collection<EstadoInscripcion> estados);

    /**
     * Los borradores del torneo que son de ese usuario, el más viejo primero: el de la individual y los de los
     * equipos de los que es capitán.
     */
    @Query("select i.id from Inscripcion i left join i.equipo e where i.torneo.id = :torneoId "
            + "and i.estado = com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion.BORRADOR "
            + "and (i.usuario.id = :usuarioId or e.capitan.id = :usuarioId) order by i.id")
    List<Long> borradoresDe(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId);

    /** Una inscripción activa del usuario en el torneo: sirve para decir cuál es cuando choca con otra. */
    Optional<Inscripcion> findFirstByTorneoIdAndUsuarioIdAndEstadoIn(Long torneoId, Long usuarioId, Collection<EstadoInscripcion> estados);

    /** La inscripción del equipo en el torneo: por la clave única de V9 hay una sola, en cualquier estado. */
    Optional<Inscripcion> findByTorneoIdAndEquipoId(Long torneoId, Long equipoId);

    /** Las del torneo en esos estados, por id: las confirmadas que se sellan al cerrar y las que se cancelan. */
    List<Inscripcion> findByTorneoIdAndEstadoInOrderByIdAsc(Long torneoId, Collection<EstadoInscripcion> estados);

    long countByTorneoIdAndEstado(Long torneoId, EstadoInscripcion estado);

    /** Las que entran al sorteo del bracket (PICA-215), por id. */
    @Query("select i.id from Inscripcion i where i.torneo.id = :torneoId and i.estado = :estado order by i.id")
    List<Long> idsPorTorneoYEstado(@Param("torneoId") Long torneoId, @Param("estado") EstadoInscripcion estado);

    /**
     * El torneo de la inscripción, solo si es de ese usuario (el participante de la individual, el capitán de la
     * de equipo); no carga la inscripción.
     */
    @Query("select i.torneo.id from Inscripcion i left join i.equipo e "
            + "where i.id = :id and (i.usuario.id = :usuarioId or e.capitan.id = :usuarioId)")
    Optional<Long> torneoIdDeInscripcionPropia(@Param("id") Long id, @Param("usuarioId") Long usuarioId);

    @Query("SELECT i.torneo.id AS torneoId, count(i) AS cantidad FROM Inscripcion i " +
           "WHERE i.torneo.id IN :torneoIds AND i.estado = :estado GROUP BY i.torneo.id")
    List<ConteoInscripciones> contarPorTorneoIdInYEstado(
            @Param("torneoIds") Collection<Long> torneoIds,
            @Param("estado") EstadoInscripcion estado);

    /** El estado guardado, leído de la base y no del contexto de persistencia: sirve después de esperar un bloqueo. */
    @Query("select i.estado from Inscripcion i where i.id = :id")
    Optional<EstadoInscripcion> estadoDe(@Param("id") Long id);

    /**
     * Las que armó el usuario: la individual, o las de los equipos de los que es capitán, en todos los estados.
     * {@code torneoId} es opcional. Trae el equipo en la misma consulta.
     */
    @Query(value = "select i from Inscripcion i join fetch i.torneo left join fetch i.equipo e "
            + "where (i.usuario.id = :usuarioId or e.capitan.id = :usuarioId) "
            + "and (:torneoId is null or i.torneo.id = :torneoId)",
            countQuery = "select count(i) from Inscripcion i left join i.equipo e "
                    + "where (i.usuario.id = :usuarioId or e.capitan.id = :usuarioId) "
                    + "and (:torneoId is null or i.torneo.id = :torneoId)")
    Page<Inscripcion> buscarPropias(@Param("usuarioId") Long usuarioId, @Param("torneoId") Long torneoId,
            Pageable pageable);
}
