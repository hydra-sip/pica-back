package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;
import com.hydra.pica.plataforma_pica.partido.domain.Grupo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrupoRepository extends JpaRepository<Grupo, Long> {

    List<Grupo> findByTorneoIdOrderByLetraAsc(Long torneoId);

    boolean existsByTorneoId(Long torneoId);
}
