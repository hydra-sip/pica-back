package com.hydra.pica.plataforma_pica.rifa.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Rifa no tiene @SQLRestriction: cada consulta filtra las dadas de baja a mano. */
public interface RifaRepository extends JpaRepository<Rifa, Long> {

    Page<Rifa> findByOrganizadorIdAndEliminadoEnIsNull(Long organizadorId, Pageable pageable);

    Optional<Rifa> findByIdAndOrganizadorIdAndEliminadoEnIsNull(Long id, Long organizadorId);

    Optional<Rifa> findByTorneoIdAndEliminadoEnIsNull(Long torneoId);

    List<Rifa> findByTorneoIdInAndEliminadoEnIsNull(Collection<Long> torneoIds);
}
