package com.hydra.pica.plataforma_pica.billetera.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustodiaRepository extends JpaRepository<Custodia, Long> {

    Optional<Custodia> findByTorneoId(Long torneoId);

    Optional<Custodia> findByRifaId(Long rifaId);

    /** La crea si no existe; dos primeros pagos simultáneos crean una sola. */
    @Modifying
    @Query(value = """
            INSERT INTO custodia (torneo_id, creado_por, modificado_por) VALUES (:torneoId, 'COBRO', 'COBRO')
            ON CONFLICT (torneo_id) DO NOTHING
            """, nativeQuery = true)
    void crearDelTorneo(@Param("torneoId") Long torneoId);

    @Modifying
    @Query(value = """
            INSERT INTO custodia (rifa_id, creado_por, modificado_por) VALUES (:rifaId, 'COBRO', 'COBRO')
            ON CONFLICT (rifa_id) DO NOTHING
            """, nativeQuery = true)
    void crearDeLaRifa(@Param("rifaId") Long rifaId);

    /** Para ingresar un pago: los pagos van en paralelo, pero esperan a una liberación en curso y la bloquean. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM Custodia c WHERE c.torneo.id = :torneoId")
    Optional<Custodia> findParaIngresarPorTorneoId(@Param("torneoId") Long torneoId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM Custodia c WHERE c.rifa.id = :rifaId")
    Optional<Custodia> findParaIngresarPorRifaId(@Param("rifaId") Long rifaId);

    /** Para liberarla: un pago a la vez no entra y dos liberaciones no pagan dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Custodia c WHERE c.torneo.id = :torneoId")
    Optional<Custodia> findParaLiberarPorTorneoId(@Param("torneoId") Long torneoId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Custodia c WHERE c.rifa.id = :rifaId")
    Optional<Custodia> findParaLiberarPorRifaId(@Param("rifaId") Long rifaId);

    /** Lo que pagó con saldo cada billetera: sale de su retenido al liberar. Los simulados no lo tocaron. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE billetera b
            SET saldo_retenido = b.saldo_retenido - p.total, modificado_en = now()
            FROM (SELECT billetera_id, sum(monto) AS total FROM movimiento
                  WHERE custodia_id = :custodiaId AND estado = 'CONFIRMADO' AND referencia_externa LIKE 'pago-%'
                  GROUP BY billetera_id) p
            WHERE b.id = p.billetera_id
            """, nativeQuery = true)
    int descontarDelRetenido(@Param("custodiaId") Long custodiaId);
}
