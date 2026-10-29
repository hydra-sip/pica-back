package com.hydra.pica.plataforma_pica.rifa.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Rifa no tiene @SQLRestriction: cada consulta filtra las dadas de baja a mano. Las del organizador traen el
 * torneo (PICA-218): la respuesta muestra su nombre y reemplazar los premios limpia la sesión antes de armarla.
 */
public interface RifaRepository extends JpaRepository<Rifa, Long> {

    @EntityGraph(attributePaths = "torneo")
    Page<Rifa> findByOrganizadorIdAndEliminadoEnIsNull(Long organizadorId, Pageable pageable);

    @EntityGraph(attributePaths = "torneo")
    Optional<Rifa> findByIdAndOrganizadorIdAndEliminadoEnIsNull(Long id, Long organizadorId);

    /** Las que se pueden comprar o se van a poder: en venta y con la venta sin terminar (HU-403). */
    @EntityGraph(attributePaths = "torneo")
    Page<Rifa> findByEstadoAndVentaHastaAfterAndEliminadoEnIsNull(EstadoRifa estado, Instant ahora, Pageable pageable);

    @EntityGraph(attributePaths = "torneo")
    Optional<Rifa> findByIdAndEliminadoEnIsNull(Long id);

    Optional<Rifa> findByTorneoIdAndEliminadoEnIsNull(Long torneoId);

    List<Rifa> findByTorneoIdInAndEliminadoEnIsNull(Collection<Long> torneoIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rifa r where r.id = :id and r.eliminadoEn is null")
    Optional<Rifa> buscarBloqueado(@Param("id") Long id);
}


