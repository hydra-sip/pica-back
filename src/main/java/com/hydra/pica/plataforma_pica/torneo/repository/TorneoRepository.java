package com.hydra.pica.plataforma_pica.torneo.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TorneoRepository extends JpaRepository<Torneo, Long> {

    Optional<Torneo> findByIdAndEliminadoEnIsNull(Long id);

    @Query("select count(t) > 0 from Torneo t join t.arbitros a where t.id = :torneoId and a.id = :usuarioId")
    boolean esArbitro(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId);
}
