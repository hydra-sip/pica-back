package com.hydra.pica.plataforma_pica.inscripcion.repository;

import java.util.Collection;
import java.util.List;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InscripcionRepository extends JpaRepository<Inscripcion, Long> {

    boolean existsByTorneoIdAndUsuarioIdAndEstadoIn(Long torneoId, Long usuarioId, Collection<EstadoInscripcion> estados);

    long countByTorneoIdAndEstado(Long torneoId, EstadoInscripcion estado);

    @Query("SELECT i.torneo.id AS torneoId, count(i) AS cantidad FROM Inscripcion i " +
           "WHERE i.torneo.id IN :torneoIds AND i.estado = :estado GROUP BY i.torneo.id")
    List<ConteoInscripciones> contarPorTorneoIdInYEstado(
            @Param("torneoIds") Collection<Long> torneoIds,
            @Param("estado") EstadoInscripcion estado);
}
