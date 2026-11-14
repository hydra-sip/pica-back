package com.hydra.pica.plataforma_pica.control.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * El nombre de lo que toca cada registro de la auditoría (HU-704): el torneo, la rifa, el usuario... Se busca al leer
 * la página, con una consulta por tipo de entidad, así sirve también para los registros viejos. Va por SQL para
 * encontrar también lo dado de baja (el {@code @SQLRestriction} de Usuario lo escondería). Lo que no se encuentra
 * queda sin descripción.
 */
@Component
@RequiredArgsConstructor
class DescripcionesAuditoria {

    /** Por entidad, la consulta que devuelve (id, descripción) para los ids de {@code :ids}. */
    private static final Map<String, String> CONSULTAS = Map.of(
            "TORNEO", "SELECT CAST(id AS TEXT), nombre FROM torneo WHERE id IN (:ids)",
            "PARTIDO", """
                    SELECT CAST(p.id AS TEXT), 'Ronda ' || p.ronda || ' – ' || t.nombre
                    FROM partido p JOIN torneo t ON t.id = p.torneo_id WHERE p.id IN (:ids)""",
            "RIFA", "SELECT CAST(id AS TEXT), titulo FROM rifa WHERE id IN (:ids)",
            "CUSTODIA", """
                    SELECT CAST(c.id AS TEXT), COALESCE('Torneo: ' || t.nombre, 'Rifa: ' || r.titulo)
                    FROM custodia c LEFT JOIN torneo t ON t.id = c.torneo_id LEFT JOIN rifa r ON r.id = c.rifa_id
                    WHERE c.id IN (:ids)""",
            "USUARIO", "SELECT CAST(id AS TEXT), '@' || username FROM usuario WHERE id IN (:ids)",
            "BILLETERA", """
                    SELECT CAST(b.id AS TEXT), COALESCE('@' || u.username, 'Plataforma')
                    FROM billetera b LEFT JOIN usuario u ON u.id = b.usuario_id WHERE b.id IN (:ids)""",
            "CARGA_CRIPTO", """
                    SELECT CAST(c.id AS TEXT), '@' || u.username
                    FROM carga_cripto c JOIN usuario u ON u.id = c.usuario_id WHERE c.id IN (:ids)""");

    private static final String PARAMETROS =
            "SELECT clave, descripcion FROM parametro_plataforma WHERE clave IN (:ids)";

    private final EntityManager entityManager;

    /** Clave: {@link #clave}. */
    Map<String, String> de(Collection<Auditoria> registros) {
        Map<String, Set<String>> idsPorEntidad = registros.stream()
                .filter(a -> a.getEntidad() != null && a.getEntidadId() != null)
                .collect(Collectors.groupingBy(Auditoria::getEntidad,
                        Collectors.mapping(Auditoria::getEntidadId, Collectors.toSet())));

        Map<String, String> descripciones = new HashMap<>();
        idsPorEntidad.forEach((entidad, ids) -> {
            String consulta = "PARAMETRO".equals(entidad) ? PARAMETROS : CONSULTAS.get(entidad);
            if (consulta == null) {
                return;
            }
            Collection<?> parametro = "PARAMETRO".equals(entidad) ? ids : numericos(ids);
            if (parametro.isEmpty()) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Object[]> filas = entityManager.createNativeQuery(consulta)
                    .setParameter("ids", parametro)
                    .getResultList();
            filas.forEach(fila -> descripciones.put(clave(entidad, (String) fila[0]), (String) fila[1]));
        });
        return descripciones;
    }

    static String clave(String entidad, String entidadId) {
        return entidad + ":" + entidadId;
    }

    private static List<Long> numericos(Set<String> ids) {
        return ids.stream().filter(id -> id.matches("\\d{1,18}")).map(Long::valueOf).toList();
    }
}
