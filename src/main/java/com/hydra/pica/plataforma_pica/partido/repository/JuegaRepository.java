package com.hydra.pica.plataforma_pica.partido.repository;

import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.domain.JuegaId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JuegaRepository extends JpaRepository<Juega, JuegaId> {
}
