package com.hydra.pica.plataforma_pica.billetera.repository;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovimientoRepository extends JpaRepository<Movimiento, Long> {
}
