package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.Rol;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolRepository extends JpaRepository<Rol, Long> {

    Optional<Rol> findByNombre(String nombre);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByNombre(String nombre);
}
