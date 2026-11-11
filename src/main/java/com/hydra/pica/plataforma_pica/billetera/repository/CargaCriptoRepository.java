package com.hydra.pica.plataforma_pica.billetera.repository;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CargaCriptoRepository extends JpaRepository<CargaCripto, Long> {
}
