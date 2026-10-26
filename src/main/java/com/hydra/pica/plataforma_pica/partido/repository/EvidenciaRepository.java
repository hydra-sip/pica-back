package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.partido.domain.Evidencia;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenciaRepository extends JpaRepository<Evidencia, Long> {

    /** En el orden en que se subieron. */
    List<Evidencia> findByPartidoIdOrderByCreadoEnAscIdAsc(Long partidoId);
}
