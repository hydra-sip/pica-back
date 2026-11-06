package com.hydra.pica.plataforma_pica.torneo.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArbitroDesignadoRepository extends JpaRepository<ArbitroDesignado, ArbitroDesignadoId> {

    /** Con el usuario y su persona, en el orden en que se designaron. */
    @Query("""
            select a from ArbitroDesignado a join fetch a.usuario u join fetch u.persona
            where a.id.torneoId = :torneoId
            order by a.designadoEn, a.id.usuarioId""")
    List<ArbitroDesignado> delTorneo(@Param("torneoId") Long torneoId);

    /** Las del árbitro (HU-807), con el torneo y su organizador; los torneos dados de baja no aparecen. */
    @Query("""
            select a from ArbitroDesignado a join fetch a.torneo t join fetch t.organizador o join fetch o.persona
            where a.id.usuarioId = :usuarioId and t.eliminadoEn is null
            order by a.designadoEn desc, a.id.torneoId desc""")
    List<ArbitroDesignado> delArbitro(@Param("usuarioId") Long usuarioId);
}
