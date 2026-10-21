package com.hydra.pica.plataforma_pica.torneo.repository;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TorneoRepository extends JpaRepository<Torneo, Long> {
}
