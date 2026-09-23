package com.hydra.pica.plataforma_pica.user.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class UsuarioRepositoryImpl implements UsuarioAdminRepositoryCustom {

    private final EntityManager entityManager;

    public UsuarioRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<UsuarioAdminRow> buscarIncluyendoEliminados(
            String q, EstadoUsuario estado, Long rolId, Pageable pageable) {
        String filters = """
                WHERE (CAST(:q AS text) IS NULL OR
                    lower(u.username) LIKE :q OR
                    lower(u.email) LIKE :q OR
                    lower(p.apellidos) LIKE :q OR
                    lower(p.nro_doc) LIKE :q)
                  AND (CAST(:estado AS varchar) IS NULL OR u.estado = :estado)
                  AND (CAST(:rolId AS bigint) IS NULL OR EXISTS (
                    SELECT 1 FROM usuario_rol urf
                    WHERE urf.usuario_id = u.id AND urf.rol_id = :rolId))
                """;
        String from = """
                FROM usuario u
                JOIN persona p ON p.id = u.persona_id
                """;
        String select = """
                SELECT u.id, u.username, u.email, u.estado, u.eliminado_en, u.creado_en,
                       p.id, p.apellidos || ', ' || p.nombres, p.tipo_doc, p.nro_doc,
                       COALESCE(jsonb_agg(jsonb_build_object(
                           'id', r.id, 'nombre', r.nombre, 'nombreAmigable', r.nombre_amigable)
                           ORDER BY r.nombre) FILTER (WHERE r.id IS NOT NULL), '[]')::text
                """ + from + """
                LEFT JOIN usuario_rol ur ON ur.usuario_id = u.id
                LEFT JOIN rol r ON r.id = ur.rol_id
                """ + filters + """
                GROUP BY u.id, p.id
                """ + orderBy(pageable) + " LIMIT :limit OFFSET :offset";
        String count = "SELECT COUNT(*) " + from + filters;

        Map<String, Object> parameters = parameters(q, estado, rolId);
        Query dataQuery = entityManager.createNativeQuery(select);
        Query countQuery = entityManager.createNativeQuery(count);
        parameters.forEach((name, value) -> {
            dataQuery.setParameter(name, value);
            countQuery.setParameter(name, value);
        });
        dataQuery.setParameter("limit", pageable.getPageSize());
        dataQuery.setParameter("offset", pageable.getOffset());

        List<UsuarioAdminRow> rows = new ArrayList<>();
        for (Object result : dataQuery.getResultList()) {
            Object[] row = (Object[]) result;
            rows.add(new UsuarioAdminRow(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    (String) row[2],
                    EstadoUsuario.valueOf((String) row[3]),
                    toInstant(row[4]),
                    toInstant(row[5]),
                    ((Number) row[6]).longValue(),
                    (String) row[7],
                    (String) row[8],
                    (String) row[9],
                    (String) row[10]));
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(rows, pageable, total);
    }

    private Map<String, Object> parameters(String q, EstadoUsuario estado, Long rolId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%");
        parameters.put("estado", estado == null ? null : estado.name());
        parameters.put("rolId", rolId);
        return parameters;
    }

    private String orderBy(Pageable pageable) {
        Map<String, String> columns = Map.of(
                "id", "u.id",
                "username", "u.username",
                "email", "u.email",
                "estado", "u.estado",
                "creadoEn", "u.creado_en",
                "persona.apellidos", "p.apellidos",
                "persona.nombres", "p.nombres",
                "apellidos", "p.apellidos",
                "nombres", "p.nombres");
        String order = pageable.getSort().stream()
                .filter(orderEntry -> columns.containsKey(orderEntry.getProperty()))
                .map(orderEntry -> columns.get(orderEntry.getProperty()) + " " + orderEntry.getDirection())
                .reduce((left, right) -> left + ", " + right)
                .orElse("u.creado_en DESC");
        return "ORDER BY " + order;
    }

    private Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return ((Timestamp) value).toInstant();
    }
}
