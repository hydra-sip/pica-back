package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Collection;
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

    /**
     * Cuántos usuarios tiene cada rol, para el listado de roles. Cuenta asignaciones vigentes de
     * usuarios no eliminados, en cualquier estado.
     *
     * El {@code eliminadoEn is null} del usuario va escrito a propósito: si la consulta no usa
     * ninguna columna de Usuario, Hibernate saca el join (la relación es obligatoria) y con él se
     * va el {@code @SQLRestriction}, así que contaba también a los eliminados. Filtra por
     * {@code id.rolId} y no por {@code rol} para no sumar el join con Rol, que dejaría sin contar a
     * los roles dados de baja.
     */
    @Query("""
            select usuarioRol.id.rolId as rolId, count(usuarioRol) as cantidad
            from UsuarioRol usuarioRol
              join usuarioRol.usuario usuario
            where usuarioRol.id.rolId in :rolIds
              and usuario.eliminadoEn is null
            group by usuarioRol.id.rolId
            """)
    List<CantidadPorRol> contarUsuariosPorRol(@Param("rolIds") Collection<Long> rolIds);

    interface CantidadPorRol {
        Long getRolId();

        long getCantidad();
    }
}
