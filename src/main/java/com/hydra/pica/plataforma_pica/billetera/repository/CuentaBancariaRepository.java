package com.hydra.pica.plataforma_pica.billetera.repository;

import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CuentaBancariaRepository extends JpaRepository<CuentaBancaria, Long> {
}
