package com.hydra.pica.plataforma_pica.torneo.repository;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
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
}
