package com.hydra.pica.plataforma_pica.inscripcion.repository;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InscripcionRepository extends JpaRepository<Inscripcion, Long> {
}
