package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.Persona;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonaRepository extends JpaRepository<Persona, Long> {

    Optional<Persona> findByTipoDocAndNroDoc(String tipoDoc, String nroDoc);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByTipoDocAndNroDoc(String tipoDoc, String nroDoc);
}
