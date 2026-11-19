package com.hydra.pica.plataforma_pica.control.repository;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.control.domain.AlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.EstadoAlertaFraude;
import com.hydra.pica.plataforma_pica.control.domain.ReglaFraude;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filtros combinables para el panel de alertas de fraude (HU-705). Cada filtro sin valor devuelve null.
 */
public final class AlertaFraudeSpecifications {

    private AlertaFraudeSpecifications() {
    }

    public static Specification<AlertaFraude> conEstado(EstadoAlertaFraude estado) {
        if (estado == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("estado"), estado);
    }

    public static Specification<AlertaFraude> conRegla(ReglaFraude regla) {
        if (regla == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("regla"), regla);
    }

    public static Specification<AlertaFraude> delTorneo(Long torneoId) {
        if (torneoId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("torneo").get("id"), torneoId);
    }

    public static Specification<AlertaFraude> deLaRifa(Long rifaId) {
        if (rifaId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("rifa").get("id"), rifaId);
    }

    public static Specification<AlertaFraude> delUsuario(Long usuarioId) {
        if (usuarioId == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("usuario").get("id"), usuarioId);
    }

    public static Specification<AlertaFraude> desde(Instant desde) {
        if (desde == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("creadaEn"), desde);
    }

    /** Excluye el instante superior, consistente con {@link AuditoriaSpecifications#hasta(Instant)}. */
    public static Specification<AlertaFraude> hasta(Instant hasta) {
        if (hasta == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThan(root.get("creadaEn"), hasta);
    }
}
