package com.hydra.pica.plataforma_pica.torneo.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.torneo.domain.Reprogramacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReprogramacionRepository extends JpaRepository<Reprogramacion, Long> {

    /** El historial del torneo, de la más vieja a la más nueva. */
    List<Reprogramacion> findByTorneoIdOrderByIdAsc(Long torneoId);

    /** La que tiene el plazo sin resolver, si hay: la base admite una sola por torneo. */
    Optional<Reprogramacion> findByTorneoIdAndPlazoProcesadoEnIsNull(Long torneoId);

    boolean existsByTorneoIdAndPlazoProcesadoEnIsNull(Long torneoId);

    /** Los torneos con el plazo de rechazo vencido y sin resolver, para {@code VencimientoReprogramacion}. */
    @Query("select r.torneo.id from Reprogramacion r where r.plazoProcesadoEn is null and r.rechazoHasta <= :ahora "
            + "order by r.rechazoHasta, r.id")
    List<Long> torneosConPlazoVencido(@Param("ahora") Instant ahora);

    /**
     * Los torneos cuyo plazo de rechazo vence antes de {@code hasta} y todavía no tuvieron el recordatorio (CR-01-4),
     * para {@code VencimientoReprogramacion}.
     */
    @Query("select r.torneo.id from Reprogramacion r where r.plazoProcesadoEn is null "
            + "and r.recordatorioEnviadoEn is null and r.rechazoHasta > :ahora and r.rechazoHasta <= :hasta "
            + "order by r.rechazoHasta, r.id")
    List<Long> torneosParaRecordar(@Param("ahora") Instant ahora, @Param("hasta") Instant hasta);
}
