package com.hydra.pica.plataforma_pica.torneo.repository;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.repository.BusquedaSinTildes;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

public final class TorneoSpecifications {

    private TorneoSpecifications() {
    }

    public static Specification<Torneo> noEliminado() {
        return (root, query, cb) -> cb.isNull(root.get("eliminadoEn"));
    }

    public static Specification<Torneo> noBorrador() {
        return (root, query, cb) -> cb.notEqual(root.get("estado"), EstadoTorneo.BORRADOR);
    }

    public static Specification<Torneo> conDisciplina(String disciplina) {
        if (disciplina == null || disciplina.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(cb.lower(root.get("disciplina")), disciplina.trim().toLowerCase());
    }

    public static Specification<Torneo> conEstado(EstadoTorneo estado) {
        if (estado == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("estado"), estado);
    }

    /** Cada palabra tiene que aparecer en el nombre, la disciplina o la sede, sin importar tildes ni mayúsculas. */
    public static Specification<Torneo> conTexto(String q) {
        List<String> patrones = BusquedaSinTildes.patronesPorPalabra(q);
        if (patrones.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            List<Expression<String>> columnas = List.of(root.get("nombre"), root.get("disciplina"), root.get("sede"));
            return cb.and(patrones.stream()
                    .map(patron -> BusquedaSinTildes.algunaContiene(cb, patron, columnas))
                    .toArray(Predicate[]::new));
        };
    }

    public static Specification<Torneo> conModalidad(Modalidad modalidad) {
        if (modalidad == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("modalidad"), modalidad);
    }

    /**
     * Orden por defecto: primero los que todavía no empezaron, del más cercano al más lejano, y después los ya
     * empezados. Un sort explícito del Pageable lo reemplaza. No filtra nada; el conteo de la página no lleva orden.
     */
    public static Specification<Torneo> proximosPrimero() {
        return (root, query, cb) -> {
            if (!Long.class.equals(query.getResultType())) {
                Expression<Instant> fechaInicio = root.get("fechaInicio");
                query.orderBy(
                        cb.asc(cb.selectCase().when(cb.greaterThanOrEqualTo(fechaInicio, Instant.now()), 0).otherwise(1)),
                        cb.asc(fechaInicio),
                        cb.asc(root.get("id")));
            }
            return null;
        };
    }
}
