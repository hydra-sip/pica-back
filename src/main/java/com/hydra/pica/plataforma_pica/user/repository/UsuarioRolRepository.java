package com.hydra.pica.plataforma_pica.user.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRolId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsuarioRolRepository extends JpaRepository<UsuarioRol, UsuarioRolId> {

    @Query("select usuarioRol from UsuarioRol usuarioRol where usuarioRol.id.usuarioId = :usuarioId")
    List<UsuarioRol> findByUsuarioId(@Param("usuarioId") Long usuarioId);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    @Query("""
            select case when count(usuarioRol) > 0 then true else false end
            from UsuarioRol usuarioRol
            where usuarioRol.id.usuarioId = :usuarioId
              and usuarioRol.id.rolId = :rolId
            """)
    boolean existsByUsuarioIdAndRolId(@Param("usuarioId") Long usuarioId, @Param("rolId") Long rolId);
}
