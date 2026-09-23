package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.Permiso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermisoRepository extends JpaRepository<Permiso, Long> {

    Optional<Permiso> findByCodigo(String codigo);

    List<Permiso> findByCodigoIn(Collection<String> codigos);

    /** Catálogo completo en el orden del seed, para agrupar por módulo en la pantalla de roles. */
    List<Permiso> findAllByOrderByIdAsc();

    /**
     * Unión de los permisos de los roles activos del usuario. Es lo que se mete en el JWT al
     * emitirlo y lo que se vuelve a calcular en cada refresh.
     *
     * El join con Usuario no es decorativo: sin él la consulta no toca la tabla usuario y no se
     * aplica ni su {@code @SQLRestriction} ni su estado, así que un usuario bloqueado o dado de
     * baja seguiría renovando el token con todos sus permisos. Las asignaciones y los roles con
     * baja lógica quedan afuera solos por el {@code @SQLRestriction} de UsuarioRol y Rol; los
     * estados (usuario y rol) hay que filtrarlos a mano.
     */
    @Query("""
            select distinct permiso.codigo
            from UsuarioRol usuarioRol
              join usuarioRol.usuario usuario
              join usuarioRol.rol rol
              join rol.permisos permiso
            where usuario.id = :usuarioId
              and usuario.estado = com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario.ACTIVO
              and rol.estado = com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral.ACTIVO
            """)
    Set<String> findCodigosByUsuarioId(@Param("usuarioId") Long usuarioId);
}
