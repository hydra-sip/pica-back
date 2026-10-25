package com.hydra.pica.plataforma_pica.inscripcion.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InscripcionRepository extends JpaRepository<Inscripcion, Long> {

    boolean existsByTorneoIdAndUsuarioIdAndEstadoIn(Long torneoId, Long usuarioId, Collection<EstadoInscripcion> estados);

    /** La inscripción del equipo en el torneo: por la clave única de V9 hay una sola, en cualquier estado. */
    Optional<Inscripcion> findByTorneoIdAndEquipoId(Long torneoId, Long equipoId);

    long countByTorneoIdAndEstado(Long torneoId, EstadoInscripcion estado);

    /** El torneo de la inscripción, solo si es de ese usuario; no carga la inscripción. */
    @Query("select i.torneo.id from Inscripcion i where i.id = :id and i.usuario.id = :usuarioId")
    Optional<Long> torneoIdDeInscripcionPropia(@Param("id") Long id, @Param("usuarioId") Long usuarioId);

    @Query("SELECT i.torneo.id AS torneoId, count(i) AS cantidad FROM Inscripcion i " +
           "WHERE i.torneo.id IN :torneoIds AND i.estado = :estado GROUP BY i.torneo.id")
    List<ConteoInscripciones> contarPorTorneoIdInYEstado(
            @Param("torneoIds") Collection<Long> torneoIds,
            @Param("estado") EstadoInscripcion estado);
}
