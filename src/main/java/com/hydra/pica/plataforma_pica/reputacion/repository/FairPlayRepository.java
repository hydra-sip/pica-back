package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import com.hydra.pica.plataforma_pica.reputacion.domain.FairPlay;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FairPlayRepository extends JpaRepository<FairPlay, Long> {

    /** Crea la fila con el valor inicial si no estaba; con dos eventos a la vez, el segundo no hace nada. */
    @Modifying
    @Query(value = """
            INSERT INTO fair_play (usuario_id, valor, actualizado_en) VALUES (:usuarioId, :inicial, now())
            ON CONFLICT (usuario_id) DO NOTHING""", nativeQuery = true)
    void crearSiFalta(@Param("usuarioId") Long usuarioId, @Param("inicial") int inicial);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FairPlay f where f.usuarioId = :usuarioId")
    Optional<FairPlay> bloquear(@Param("usuarioId") Long usuarioId);

    List<FairPlay> findByUsuarioIdIn(Collection<Long> usuarioIds);
}
