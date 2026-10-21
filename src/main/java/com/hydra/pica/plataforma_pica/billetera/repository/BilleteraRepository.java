package com.hydra.pica.plataforma_pica.billetera.repository;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BilleteraRepository extends JpaRepository<Billetera, Long> {
}
