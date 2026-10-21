package com.hydra.pica.plataforma_pica.torneo.repository;

import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.PremioId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PremioRepository extends JpaRepository<Premio, PremioId> {
}
