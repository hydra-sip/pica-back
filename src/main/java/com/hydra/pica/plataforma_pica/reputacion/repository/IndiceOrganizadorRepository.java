package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.util.Optional;
import com.hydra.pica.plataforma_pica.reputacion.domain.IndiceOrganizador;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IndiceOrganizadorRepository extends JpaRepository<IndiceOrganizador, Long> {

    /** Crea la fila con el valor inicial si no estaba; con dos eventos a la vez, el segundo no hace nada. */
    @Modifying
    @Query(value = """
            INSERT INTO indice_organizador (usuario_id, valor, actualizado_en) VALUES (:usuarioId, :inicial, now())
            ON CONFLICT (usuario_id) DO NOTHING""", nativeQuery = true)
    void crearSiFalta(@Param("usuarioId") Long usuarioId, @Param("inicial") int inicial);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IndiceOrganizador i where i.usuarioId = :usuarioId")
    Optional<IndiceOrganizador> bloquear(@Param("usuarioId") Long usuarioId);
}
