package com.hydra.pica.plataforma_pica.torneo.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.torneo.domain.Cancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoCancelacion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CancelacionRepository extends JpaRepository<Cancelacion, Long> {

    Optional<Cancelacion> findByTorneoId(Long torneoId);

    /** La cola de soporte: de la más vieja a la más nueva, con el torneo y su organizador. */
    @Query(value = """
            SELECT c FROM Cancelacion c JOIN FETCH c.torneo t JOIN FETCH t.organizador o LEFT JOIN FETCH o.persona
            WHERE c.estado = :estado ORDER BY c.creadoEn, c.id""",
            countQuery = "SELECT count(c) FROM Cancelacion c WHERE c.estado = :estado")
    Page<Cancelacion> conEstado(@Param("estado") EstadoCancelacion estado, Pageable pageable);
}
