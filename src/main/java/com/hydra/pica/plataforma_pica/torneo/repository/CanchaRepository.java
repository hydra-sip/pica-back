package com.hydra.pica.plataforma_pica.torneo.repository;

import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanchaRepository extends JpaRepository<Cancha, Long> {
}
