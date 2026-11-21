package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.reputacion.domain.Insignia;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InsigniaRepository extends JpaRepository<Insignia, String> {

    List<Insignia> findAllByOrderByCodigoAsc();
}
