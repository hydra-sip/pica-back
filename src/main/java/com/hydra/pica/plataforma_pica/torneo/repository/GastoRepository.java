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

    /** Los gastos que no son honorarios arbitrales: se deducen aunque se paguen por fuera (HU-604). */
    @Query("""
            select coalesce(sum(g.monto), 0) from Gasto g
            where g.torneo.id = :torneoId and g.tipo = com.hydra.pica.plataforma_pica.torneo.domain.TipoGasto.OTRO""")
    BigDecimal totalComunes(@Param("torneoId") Long torneoId);

    /** Los honorarios depositados en una custodia y vigentes: los que la liquidación le paga a cada árbitro. */
    @Query("""
            select g from Gasto g join fetch g.movimiento m join fetch g.beneficiario
            where m.custodia.id = :custodiaId
              and m.estado = com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento.CONFIRMADO
            order by g.id.gastoId""")
    List<Gasto> honorariosEnCustodia(@Param("custodiaId") Long custodiaId);

    /** Los honorarios depositados para un árbitro en un torneo. */
    @Query("""
            select g from Gasto g join fetch g.movimiento
            where g.torneo.id = :torneoId and g.beneficiario.id = :usuarioId
            order by g.id.gastoId""")
    List<Gasto> depositadosDelArbitro(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId);

    /** Los honorarios depositados para un árbitro, en todos sus torneos, con la custodia de cada depósito. */
    @Query("""
            select g from Gasto g join fetch g.movimiento m left join fetch m.custodia
            where g.beneficiario.id = :usuarioId""")
    List<Gasto> honorariosDelArbitro(@Param("usuarioId") Long usuarioId);

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
