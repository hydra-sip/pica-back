package com.hydra.pica.plataforma_pica.equipo.repository;

import com.hydra.pica.plataforma_pica.equipo.domain.UsuarioEquipo;
import com.hydra.pica.plataforma_pica.equipo.domain.UsuarioEquipoId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioEquipoRepository extends JpaRepository<UsuarioEquipo, UsuarioEquipoId> {
}
