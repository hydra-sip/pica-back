package com.hydra.pica.plataforma_pica.partido.repository;

import com.hydra.pica.plataforma_pica.partido.domain.Pronostico;
import com.hydra.pica.plataforma_pica.partido.domain.PronosticoId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PronosticoRepository extends JpaRepository<Pronostico, PronosticoId> {
}
