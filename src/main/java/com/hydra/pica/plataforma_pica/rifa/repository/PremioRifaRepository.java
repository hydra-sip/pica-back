package com.hydra.pica.plataforma_pica.rifa.repository;

import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifaId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PremioRifaRepository extends JpaRepository<PremioRifa, PremioRifaId> {
}
