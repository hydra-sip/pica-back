package com.hydra.pica.plataforma_pica.control.repository;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.hibernate.query.criteria.JpaExpression;
import org.springframework.data.jpa.domain.Specification;

/** Los filtros del libro de auditoría (HU-704). Cada uno es null sin valor, y Spring lo ignora. */
public final class AuditoriaSpecifications {

    private static final String TORNEO = "TORNEO";

    private AuditoriaSpecifications() {
    }

    /**
     * Los registros de una entidad: sin id, todos los de ese tipo; con id, los de esa. Un torneo trae además lo suyo
     * (ver {@link #delTorneo}); el resto es la coincidencia exacta.
     */
    public static Specification<Auditoria> deLaEntidad(String entidad, String entidadId) {
        if (entidad == null || entidad.isBlank()) {
            return null;
        }
        if (entidadId == null || entidadId.isBlank()) {
            return (root, query, cb) -> cb.equal(root.get("entidad"), entidad);
        }
        if (TORNEO.equals(entidad)) {
            return delTorneo(entidadId.trim());
        }
        return (root, query, cb) -> cb.and(cb.equal(root.get("entidad"), entidad),
                cb.equal(root.get("entidadId"), entidadId.trim()));
    }

    /**
     * Lo del torneo y lo suyo: el torneo, sus partidos, su rifa y la custodia del torneo o de su rifa. Las inscripciones
     * no tienen registros propios: lo que pagan queda en la custodia. Un id que no es un número no trae nada.
     */
    private static Specification<Auditoria> delTorneo(String id) {
        if (!id.matches("\\d{1,18}")) {
            return (root, query, cb) -> cb.disjunction();
        }
        long torneoId = Long.parseLong(id);
        return (root, query, cb) -> {
            Subquery<String> partidos = query.subquery(String.class);
            Root<Partido> partido = partidos.from(Partido.class);
            partidos.select(comoTexto(partido.get("id")))
                    .where(cb.equal(partido.get("torneo").get("id"), torneoId));

            Subquery<String> rifas = query.subquery(String.class);
            Root<Rifa> rifa = rifas.from(Rifa.class);
            rifas.select(comoTexto(rifa.get("id")))
                    .where(cb.equal(rifa.get("torneo").get("id"), torneoId));

            Subquery<String> custodias = query.subquery(String.class);
            Root<Custodia> custodia = custodias.from(Custodia.class);
            Join<Custodia, Rifa> rifaDeLaCustodia = custodia.join("rifa", JoinType.LEFT);
            custodias.select(comoTexto(custodia.get("id")))
                    .where(cb.or(cb.equal(custodia.get("torneo").get("id"), torneoId),
                            cb.equal(rifaDeLaCustodia.get("torneo").get("id"), torneoId)));

            return cb.or(
                    entidad(root, cb, TORNEO, cb.equal(root.get("entidadId"), String.valueOf(torneoId))),
                    entidad(root, cb, "PARTIDO", root.get("entidadId").in(partidos)),
                    entidad(root, cb, "RIFA", root.get("entidadId").in(rifas)),
                    entidad(root, cb, "CUSTODIA", root.get("entidadId").in(custodias)));
        };
    }

    public static Specification<Auditoria> delUsuario(Long usuarioId) {
        if (usuarioId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("usuario").get("id"), usuarioId);
    }

    public static Specification<Auditoria> conAccion(AccionAuditoria accion) {
        if (accion == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("accion"), accion);
    }

    public static Specification<Auditoria> desde(Instant desde) {
        if (desde == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("creadoEn"), desde);
    }

    /** Excluye el instante: con desde = día 1 y hasta = día 2 a las 00:00, solo lo del día 1. */
    public static Specification<Auditoria> hasta(Instant hasta) {
        if (hasta == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThan(root.get("creadoEn"), hasta);
    }

    private static Predicate entidad(Root<Auditoria> root, CriteriaBuilder cb, String entidad, Predicate id) {
        return cb.and(cb.equal(root.get("entidad"), entidad), id);
    }

    /** entidad_id es texto: los ids se comparan convertidos. */
    private static Expression<String> comoTexto(Expression<Long> id) {
        return ((JpaExpression<Long>) id).cast(String.class);
    }
}
