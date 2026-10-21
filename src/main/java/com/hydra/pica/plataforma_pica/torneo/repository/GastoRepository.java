package com.hydra.pica.plataforma_pica.torneo.repository;

import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
import com.hydra.pica.plataforma_pica.torneo.domain.GastoId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GastoRepository extends JpaRepository<Gasto, GastoId> {
}
