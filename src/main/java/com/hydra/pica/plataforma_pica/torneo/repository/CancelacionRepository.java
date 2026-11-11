package com.hydra.pica.plataforma_pica.torneo.repository;

import com.hydra.pica.plataforma_pica.torneo.domain.Cancelacion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelacionRepository extends JpaRepository<Cancelacion, Long> {
}
