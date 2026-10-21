package com.hydra.pica.plataforma_pica.billetera.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BilleteraRepository extends JpaRepository<Billetera, Long> {

    Optional<Billetera> findByUsuarioId(Long usuarioId);
}
