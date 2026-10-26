package com.hydra.pica.plataforma_pica.torneo.repository;

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
}
