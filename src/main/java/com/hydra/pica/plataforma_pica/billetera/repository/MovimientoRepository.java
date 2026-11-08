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
}
