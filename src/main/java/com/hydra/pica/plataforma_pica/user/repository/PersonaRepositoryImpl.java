package com.hydra.pica.plataforma_pica.user.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class PersonaRepositoryImpl implements PersonaAdminRepositoryCustom {

    private static final Map<String, String> COLUMNAS = Map.of(
            "id", "p.id",
            "nombres", "p.nombres",
            "apellidos", "p.apellidos",
            "nroDoc", "p.nro_doc",
            "estado", "p.estado",
            "creadoEn", "p.creado_en");

    private final EntityManager entityManager;

    public PersonaRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<PersonaAdminRow> buscar(
            String q, EstadoGeneral estado, boolean incluirEliminados, Pageable pageable) {
        String from = " FROM persona p ";
        String filtros = """
                WHERE (CAST(:q AS text) IS NULL OR
                       lower(p.apellidos) LIKE :q OR
                       lower(p.nro_doc) LIKE :q)
                  AND (CAST(:estado AS varchar) IS NULL OR p.estado = :estado)
                  AND (CAST(:incluirEliminados AS boolean) OR p.eliminado_en IS NULL)
                """;
        // el índice único de usuario.persona_id cuenta a los usuarios eliminados: por eso EXISTS sin filtrar
        String select = """
                SELECT p.id, p.nombres, p.apellidos, p.tipo_doc, p.nro_doc, p.estado, p.eliminado_en,
                       EXISTS (SELECT 1 FROM usuario u WHERE u.persona_id = p.id)
                """ + from + filtros + orderBy(pageable) + " LIMIT :limit OFFSET :offset";
        String count = "SELECT COUNT(*)" + from + filtros;

        Query dataQuery = entityManager.createNativeQuery(select);
        Query countQuery = entityManager.createNativeQuery(count);
        for (Query query : List.of(dataQuery, countQuery)) {
            query.setParameter("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%");
            query.setParameter("estado", estado == null ? null : estado.name());
            query.setParameter("incluirEliminados", incluirEliminados);
        }
        dataQuery.setParameter("limit", pageable.getPageSize());
        dataQuery.setParameter("offset", pageable.getOffset());

        List<PersonaAdminRow> filas = new ArrayList<>();
        for (Object resultado : dataQuery.getResultList()) {
            Object[] fila = (Object[]) resultado;
            filas.add(new PersonaAdminRow(
                    ((Number) fila[0]).longValue(),
                    (String) fila[1],
                    (String) fila[2],
                    (String) fila[3],
                    (String) fila[4],
                    EstadoGeneral.valueOf((String) fila[5]),
                    aInstant(fila[6]),
                    (Boolean) fila[7]));
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(filas, pageable, total);
    }

    /** Solo columnas de la lista blanca; el id va siempre al final para que la paginación sea estable. */
    private String orderBy(Pageable pageable) {
        String orden = pageable.getSort().stream()
                .filter(criterio -> COLUMNAS.containsKey(criterio.getProperty()))
                .map(criterio -> COLUMNAS.get(criterio.getProperty()) + " " + criterio.getDirection())
                .reduce((izquierda, derecha) -> izquierda + ", " + derecha)
                .orElse("p.apellidos ASC, p.nombres ASC");
        return " ORDER BY " + orden + ", p.id ASC";
    }

    private Instant aInstant(Object valor) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof Instant instant) {
            return instant;
        }
        if (valor instanceof OffsetDateTime fecha) {
            return fecha.toInstant();
        }
        return ((Timestamp) valor).toInstant();
    }
}
