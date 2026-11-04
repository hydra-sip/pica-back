package com.hydra.pica.plataforma_pica.rifa.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReclamoRifaRepository extends JpaRepository<ReclamoRifa, Long> {

    List<ReclamoRifa> findByRifaIdOrderByCreadoEnDesc(Long rifaId);

    boolean existsByRifaIdAndUsuarioIdAndEstado(Long rifaId, Long usuarioId, EstadoReclamo estado);

    long countByRifaIdAndEstado(Long rifaId, EstadoReclamo estado);
}

