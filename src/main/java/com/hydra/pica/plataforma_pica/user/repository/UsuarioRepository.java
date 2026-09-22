package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmailIgnoreCase(String email);

    Optional<Usuario> findByUsernameIgnoreCase(String username);

    Optional<Usuario> findByGoogleSub(String googleSub);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByEmailIgnoreCase(String email);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByUsernameIgnoreCase(String username);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByGoogleSub(String googleSub);
}
