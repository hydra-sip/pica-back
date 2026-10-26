package com.hydra.pica.plataforma_pica.torneo.repository;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TorneoRepository extends JpaRepository<Torneo, Long>, JpaSpecificationExecutor<Torneo> {

    Optional<Torneo> findByIdAndEliminadoEnIsNull(Long id);

    /** Bloquea la fila del torneo hasta el fin de la transacción: serializa los chequeos de cupo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Torneo t where t.id = :id")
    Optional<Torneo> buscarBloqueado(@Param("id") Long id);

    /** Designado en cualquier estado, aunque todavía no haya aceptado (HU-203). */
    @Query("select count(a) > 0 from ArbitroDesignado a where a.id.torneoId = :torneoId and a.id.usuarioId = :usuarioId")
    boolean esArbitro(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId);

    /** Incluye los dados de baja: el slug es único en toda la tabla. */
    boolean existsBySlug(String slug);

    /** Un torneo propio del organizador; el de otro no aparece. */
    Optional<Torneo> findByIdAndOrganizadorIdAndEliminadoEnIsNull(Long id, Long organizadorId);

    /** Para el filtro de Mis torneos: las que usó el organizador, sin repetir. */
    @Query("""
            select distinct t.disciplina from Torneo t
            where t.organizador.id = :organizadorId and t.eliminadoEn is null
            order by t.disciplina""")
    List<String> disciplinasDelOrganizador(@Param("organizadorId") Long organizadorId);

    @EntityGraph(attributePaths = {"organizador", "organizador.persona"})
    Optional<Torneo> findBySlugAndEliminadoEnIsNull(String slug);
}
