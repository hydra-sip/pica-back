package com.hydra.pica.plataforma_pica.inscripcion.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Reemplazo;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReemplazoRepository extends JpaRepository<Reemplazo, Long> {

    @EntityGraph(attributePaths = {"sale.persona", "entra.persona", "realizadoPor.persona"})
    List<Reemplazo> findAllByInscripcionIdOrderByCreadoEnAscIdAsc(Long inscripcionId);
}
