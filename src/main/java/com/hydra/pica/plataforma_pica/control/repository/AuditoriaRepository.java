package com.hydra.pica.plataforma_pica.control.repository;

import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {
}
