package com.hydra.pica.plataforma_pica.torneo.repository;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.GastoCancelacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GastoCancelacionRepository extends JpaRepository<GastoCancelacion, Long> {

    List<GastoCancelacion> findByCancelacionIdOrderByIdAsc(Long cancelacionId);

    @Query("SELECT coalesce(sum(g.monto), 0) FROM GastoCancelacion g WHERE g.cancelacion.id = :cancelacionId")
    BigDecimal totalDeLaCancelacion(@Param("cancelacionId") Long cancelacionId);
}
