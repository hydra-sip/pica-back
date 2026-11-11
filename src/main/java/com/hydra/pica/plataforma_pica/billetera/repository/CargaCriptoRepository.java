package com.hydra.pica.plataforma_pica.billetera.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CargaCriptoRepository extends JpaRepository<CargaCripto, Long> {

    Optional<CargaCripto> findByReferenciaAndUsuarioId(String referencia, Long usuarioId);

    boolean existsByHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CargaCripto c WHERE c.id = :id")
    Optional<CargaCripto> findConBloqueoPorId(@Param("id") Long id);
}
