package com.hydra.pica.plataforma_pica.torneo.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TorneoRepository extends JpaRepository<Torneo, Long> {

    Optional<Torneo> findByIdAndEliminadoEnIsNull(Long id);

    /** Bloquea la fila del torneo hasta el fin de la transacción: serializa los chequeos de cupo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Torneo t where t.id = :id")
    Optional<Torneo> buscarBloqueado(@Param("id") Long id);

    @Query("select count(t) > 0 from Torneo t join t.arbitros a where t.id = :torneoId and a.id = :usuarioId")
    boolean esArbitro(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId);

    /** Un torneo propio del organizador; el de otro no aparece. */
    Optional<Torneo> findByIdAndOrganizadorIdAndEliminadoEnIsNull(Long id, Long organizadorId);
}
