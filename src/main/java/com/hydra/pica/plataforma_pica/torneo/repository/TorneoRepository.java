package com.hydra.pica.plataforma_pica.torneo.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

import jakarta.persistence.LockModeType;
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

    @Query("select count(t) > 0 from Torneo t join t.arbitros a where t.id = :torneoId and a.id = :usuarioId")
    boolean esArbitro(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId);

    /** Un torneo propio del organizador; el de otro no aparece. */
    Optional<Torneo> findByIdAndOrganizadorIdAndEliminadoEnIsNull(Long id, Long organizadorId);

    /** Para el filtro de Mis torneos: las que usó el organizador, sin repetir. */
    @Query("""
            select distinct t.disciplina from Torneo t
            where t.organizador.id = :organizadorId and t.eliminadoEn is null
            order by t.disciplina""")
    List<String> disciplinasDelOrganizador(@Param("organizadorId") Long organizadorId);

    /** Inscripciones en {@code estado} de cada torneo; los que no tienen ninguna no aparecen. */
    @Query("""
            select i.torneo.id as torneoId, count(i) as cantidad from Inscripcion i
            where i.torneo.id in :torneoIds and i.estado = :estado
            group by i.torneo.id""")
    List<InscripcionesPorTorneo> contarInscripciones(@Param("torneoIds") Collection<Long> torneoIds,
            @Param("estado") EstadoInscripcion estado);

    interface InscripcionesPorTorneo {
        Long getTorneoId();

        long getCantidad();
    }
}
