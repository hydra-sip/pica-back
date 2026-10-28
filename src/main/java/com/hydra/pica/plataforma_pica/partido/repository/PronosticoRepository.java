package com.hydra.pica.plataforma_pica.partido.repository;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.partido.domain.Pronostico;
import com.hydra.pica.plataforma_pica.partido.domain.PronosticoId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PronosticoRepository extends JpaRepository<Pronostico, PronosticoId> {

    @Query("select p from Pronostico p where p.id.partidoId = :partidoId")
    List<Pronostico> delPartido(@Param("partidoId") Long partidoId);

    @Query("select p from Pronostico p where p.id.usuarioId = :usuarioId")
    List<Pronostico> delUsuario(@Param("usuarioId") Long usuarioId);

    @Query("select p from Pronostico p where p.id.usuarioId = :usuarioId and p.id.partidoId = :partidoId")
    Optional<Pronostico> delUsuarioYPartido(@Param("usuarioId") Long usuarioId, @Param("partidoId") Long partidoId);
}
