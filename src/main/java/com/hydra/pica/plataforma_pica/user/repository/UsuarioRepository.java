package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;

public interface UsuarioRepository extends JpaRepository<Usuario, Long>, JpaSpecificationExecutor<Usuario> {

    Optional<Usuario> findByEmailIgnoreCase(String email);

    Optional<Usuario> findByUsernameIgnoreCase(String username);

    Optional<Usuario> findByGoogleSub(String googleSub);

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
}
