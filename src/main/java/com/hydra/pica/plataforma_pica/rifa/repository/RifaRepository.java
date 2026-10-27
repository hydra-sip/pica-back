package com.hydra.pica.plataforma_pica.rifa.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Rifa no tiene @SQLRestriction: cada consulta filtra las dadas de baja a mano. Las del organizador traen el
 * torneo (PICA-218): la respuesta muestra su nombre y reemplazar los premios limpia la sesión antes de armarla.
 */
public interface RifaRepository extends JpaRepository<Rifa, Long> {

    @EntityGraph(attributePaths = "torneo")
    Page<Rifa> findByOrganizadorIdAndEliminadoEnIsNull(Long organizadorId, Pageable pageable);

    @EntityGraph(attributePaths = "torneo")
    Optional<Rifa> findByIdAndOrganizadorIdAndEliminadoEnIsNull(Long id, Long organizadorId);

    Optional<Rifa> findByTorneoIdAndEliminadoEnIsNull(Long torneoId);

    List<Rifa> findByTorneoIdInAndEliminadoEnIsNull(Collection<Long> torneoIds);
}
