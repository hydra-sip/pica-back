package com.hydra.pica.plataforma_pica.rifa.repository;

import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.NumeroId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NumeroRepository extends JpaRepository<Numero, NumeroId> {
}
