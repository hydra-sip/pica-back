package com.hydra.pica.plataforma_pica.torneo.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
import com.hydra.pica.plataforma_pica.torneo.domain.GastoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GastoRepository extends JpaRepository<Gasto, GastoId> {

    List<Gasto> findByTorneoIdOrderByIdGastoIdAsc(Long torneoId);

    /** gasto_id numera los gastos dentro de cada torneo: el próximo es el máximo más uno. */
    @Query("select coalesce(max(g.id.gastoId), 0) from Gasto g where g.torneo.id = :torneoId")
    int ultimoNumero(@Param("torneoId") Long torneoId);

    @Query("select coalesce(sum(g.monto), 0) from Gasto g where g.torneo.id = :torneoId")
    BigDecimal totalDelTorneo(@Param("torneoId") Long torneoId);

    /** Total de gastos de cada torneo; los que no tienen ninguno no aparecen. */
    @Query("""
            select g.torneo.id as torneoId, sum(g.monto) as total from Gasto g
            where g.torneo.id in :torneoIds
            group by g.torneo.id""")
    List<GastosPorTorneo> totalesPorTorneo(@Param("torneoIds") Collection<Long> torneoIds);

    interface GastosPorTorneo {
        Long getTorneoId();

        BigDecimal getTotal();
    }
}
