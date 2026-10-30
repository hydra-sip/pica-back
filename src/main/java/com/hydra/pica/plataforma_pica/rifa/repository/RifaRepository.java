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

    /**
     * Bloqueo compartido mientras se compra (HU-407): las compras de la misma rifa no se esperan entre sí, pero el
     * cierre de la venta, que bloquea la fila, espera a que terminen. Así ninguna compra entra después del sellado.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from Rifa r where r.id = :id and r.eliminadoEn is null")
    Optional<Rifa> bloquearParaVender(@Param("id") Long id);

    /** Las que siguen EN_VENTA con la venta ya terminada (HU-407), la más vieja primero. */
    @Query("select r.id from Rifa r where r.estado = com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa.EN_VENTA "
            + "and r.eliminadoEn is null and r.ventaHasta <= :ahora order by r.ventaHasta, r.id")
    List<Long> conVentaVencida(@Param("ahora") Instant ahora);

    /** Las CERRADAS cuya fecha de sorteo ya llegó (HU-504), la más vieja primero. */
    @Query("select r.id from Rifa r where r.estado = com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa.CERRADA "
            + "and r.eliminadoEn is null and r.sorteadaEn <= :ahora order by r.sorteadaEn, r.id")
    List<Long> paraSortear(@Param("ahora") Instant ahora);

    Page<Rifa> findByEstadoInAndEliminadoEnIsNull(Collection<EstadoRifa> estados, Pageable pageable);
}


