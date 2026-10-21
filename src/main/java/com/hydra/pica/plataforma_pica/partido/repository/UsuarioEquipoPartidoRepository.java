package com.hydra.pica.plataforma_pica.partido.repository;

import com.hydra.pica.plataforma_pica.partido.domain.UsuarioEquipoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.UsuarioEquipoPartidoId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioEquipoPartidoRepository extends JpaRepository<UsuarioEquipoPartido, UsuarioEquipoPartidoId> {
}
