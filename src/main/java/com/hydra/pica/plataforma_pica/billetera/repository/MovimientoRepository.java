package com.hydra.pica.plataforma_pica.billetera.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovimientoRepository extends JpaRepository<Movimiento, Long> {

    Optional<Movimiento> findByReferenciaExterna(String referenciaExterna);

    Page<Movimiento> findByBilleteraId(Long billeteraId, Pageable pageable);

    /** Lo retenido en una custodia, por tipo de pago (HU-603): [tipo, suma, cantidad]. */
    @Query("""
            SELECT m.tipo, sum(m.monto), count(m) FROM Movimiento m
            WHERE m.custodia.id = :custodiaId AND m.estado = com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento.CONFIRMADO
            GROUP BY m.tipo
            """)
    List<Object[]> totalesDeLaCustodia(@Param("custodiaId") Long custodiaId);

    /** El pago y los gastos de una inscripción, para su comprobante; el más viejo primero. */
    @Query("""
            SELECT m FROM Movimiento m JOIN FETCH m.billetera b JOIN FETCH b.usuario
            WHERE m.inscripcion.id = :inscripcionId AND m.estado = com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento.CONFIRMADO
            ORDER BY m.id
            """)
    List<Movimiento> pagosDeLaInscripcion(@Param("inscripcionId") Long inscripcionId);

    /** Lo acreditado con esas referencias; null si no hay ninguna (todavía no se liquidó). */
    @Query("SELECT sum(m.monto) FROM Movimiento m WHERE m.referenciaExterna IN :referencias")
    BigDecimal sumaDeReferencias(@Param("referencias") List<String> referencias);

    /** Las acreditaciones de una liquidación, por el comienzo de su referencia (liq-t{id}-, liq-r{id}-…). */
    @Query("""
            SELECT m FROM Movimiento m JOIN FETCH m.billetera b LEFT JOIN FETCH b.usuario u LEFT JOIN FETCH u.persona
            LEFT JOIN FETCH m.inscripcion i LEFT JOIN FETCH i.equipo
            WHERE m.referenciaExterna LIKE :prefijo ORDER BY m.id
            """)
    List<Movimiento> deLaLiquidacion(@Param("prefijo") String prefijo);

    /**
     * Lo que pagó con saldo cada billetera en una custodia, para reintegrárselo (HU-605): [billeteraId, suma]. Los mismos
     * pagos que {@code CustodiaRepository.descontarDelRetenido} saca del retenido.
     */
    @Query("""
            SELECT m.billetera.id, sum(m.monto) FROM Movimiento m
            WHERE m.custodia.id = :custodiaId AND m.estado = com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento.CONFIRMADO
            AND m.referenciaExterna LIKE 'pago-%'
            GROUP BY m.billetera.id ORDER BY m.billetera.id
            """)
    List<Object[]> pagosConSaldoPorBilletera(@Param("custodiaId") Long custodiaId);
}
