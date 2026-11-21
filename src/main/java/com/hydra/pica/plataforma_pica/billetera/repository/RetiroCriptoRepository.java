package com.hydra.pica.plataforma_pica.billetera.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.EstadoRetiroCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.RetiroCripto;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RetiroCriptoRepository extends JpaRepository<RetiroCripto, Long> {

    Optional<RetiroCripto> findByReferenciaAndUsuarioId(String referencia, Long usuarioId);

    List<RetiroCripto> findByUsuarioIdAndEstadoOrderByIdDesc(Long usuarioId, EstadoRetiroCripto estado);

    boolean existsByHash(String hash);

    /** Los pendientes que vencieron antes de {@code limite}: la devolución automática los revisa en la cadena. */
    @Query("SELECT r.id FROM RetiroCripto r WHERE r.estado = 'PENDIENTE' AND r.venceEn < :limite ORDER BY r.id")
    List<Long> pendientesVencidosAntesDe(@Param("limite") Instant limite);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RetiroCripto r WHERE r.id = :id")
    Optional<RetiroCripto> findConBloqueoPorId(@Param("id") Long id);
}
