package com.hydra.pica.plataforma_pica.torneo.repository;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanchaRepository extends JpaRepository<Cancha, Long> {

    /** La sede es texto libre: se compara sin mayúsculas, como el nombre de la cancha. */
    List<Cancha> findBySedeIgnoreCaseAndEliminadoEnIsNullOrderByNombreAsc(String sede);

    Optional<Cancha> findFirstBySedeIgnoreCaseAndNombreIgnoreCaseAndEliminadoEnIsNull(String sede, String nombre);
}
