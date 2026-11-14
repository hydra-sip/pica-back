package com.hydra.pica.plataforma_pica.control.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long>, JpaSpecificationExecutor<Auditoria> {

    /** El libro de auditoría (HU-704), con el usuario de cada registro en la misma consulta. */
    @Override
    @EntityGraph(attributePaths = "usuario")
    Page<Auditoria> findAll(Specification<Auditoria> filtros, Pageable pageable);

    /** Los LOGIN_FALLIDO de un identificador (ya normalizado) desde {@code desde}, del más viejo al más nuevo. */
    @Query("""
            select a.creadoEn from Auditoria a
            where a.accion = com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria.LOGIN_FALLIDO
              and function('jsonb_extract_path_text', a.detalle, 'identificador') = :identificador
              and a.creadoEn > :desde
            order by a.creadoEn""")
    List<Instant> loginsFallidosDesde(@Param("identificador") String identificador, @Param("desde") Instant desde);

    /** Null si nunca inició sesión. */
    @Query("""
            select max(a.creadoEn) from Auditoria a
            where a.accion = com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria.LOGIN
              and a.usuario.id = :usuarioId""")
    Instant ultimoLogin(@Param("usuarioId") Long usuarioId);

    /** Para el filtro del libro (HU-704): los ids de una entidad con registros, primero el del último más reciente. */
    @Query(value = """
            SELECT entidad_id FROM auditoria
            WHERE entidad = :entidad AND entidad_id IS NOT NULL
            GROUP BY entidad_id
            ORDER BY max(creado_en) DESC
            LIMIT 200""", nativeQuery = true)
    List<String> idsConRegistros(@Param("entidad") String entidad);

    /** Los registros de unas entidades (por ejemplo, de los partidos de un torneo), del más viejo al más nuevo. */
    List<Auditoria> findByEntidadAndEntidadIdInOrderByCreadoEnAscIdAsc(String entidad, Collection<String> entidadIds);
}
