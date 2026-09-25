package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

public interface UsuarioRepository extends
        JpaRepository<Usuario, Long>, JpaSpecificationExecutor<Usuario>, UsuarioAdminRepositoryCustom {

    /** La persona viaja en la misma consulta (relación a uno: no rompe la paginación). */
    @Override
    @EntityGraph(attributePaths = "persona")
    Page<Usuario> findAll(Specification<Usuario> spec, Pageable pageable);

    /**
     * Carga los roles (con su rol) de los usuarios ya traídos. Va aparte del listado porque un fetch
     * de colección junto con paginación hace que Hibernate pagine en memoria; con los ids de la
     * página son 2 consultas fijas en vez de una por usuario.
     */
    @Query("select distinct u from Usuario u left join fetch u.roles ur left join fetch ur.rol where u.id in :ids")
    List<Usuario> findConRolesByIdIn(@Param("ids") Collection<Long> ids);

    Optional<Usuario> findByEmailIgnoreCase(String email);

    Optional<Usuario> findByUsernameIgnoreCase(String username);

    Optional<Usuario> findByGoogleSub(String googleSub);

    Optional<Usuario> findByTokenVerificacionHash(String tokenVerificacionHash);

    /**
     * Para el refresh: una consulta y no {@code findById}, porque el usuario ya está en la sesión como
     * proxy del refresh token y, si lo dieron de baja, {@code find} tira EntityNotFoundException en vez
     * de devolver vacío.
     */
    boolean existsByIdAndEstado(Long id, EstadoUsuario estado);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByEmailIgnoreCase(String email);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByUsernameIgnoreCase(String username);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByGoogleSub(String googleSub);

    // Las tres de abajo son nativas para saltar el @SQLRestriction: username, email y persona
    // son únicos contando a los eliminados (índices totales de V1), así que el alta tiene que
    // chequear contra todos y no solo contra los vivos. Las usa UsuarioService.crear.

    @Query(value = "SELECT EXISTS (SELECT 1 FROM usuario WHERE lower(username) = lower(:username))", nativeQuery = true)
    boolean existsByUsernameIncluyendoEliminados(@Param("username") String username);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM usuario WHERE lower(email) = lower(:email))", nativeQuery = true)
    boolean existsByEmailIncluyendoEliminados(@Param("email") String email);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM usuario WHERE persona_id = :personaId)", nativeQuery = true)
    boolean existsByPersonaIdIncluyendoEliminados(@Param("personaId") Long personaId);

    /**
     * El usuario de una persona, esté o no dado de baja: persona_id es único contando a los
     * eliminados. Para la ficha de la persona y para saber si se la puede dar de baja.
     */
    @Query(value = "SELECT * FROM usuario WHERE persona_id = :personaId", nativeQuery = true)
    Optional<Usuario> findByPersonaIdIncluyendoEliminados(@Param("personaId") Long personaId);

    /** Para la ficha de un usuario (GET /admin/usuarios/{id}): hay que poder verlo para reactivarlo. */
    @Query(value = "SELECT * FROM usuario WHERE id = :id", nativeQuery = true)
    Optional<Usuario> findByIdIncluyendoEliminados(@Param("id") Long id);
}
