package com.hydra.pica.plataforma_pica.billetera.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BilleteraRepository extends JpaRepository<Billetera, Long> {

    Optional<Billetera> findByUsuarioId(Long usuarioId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Billetera b WHERE b.usuario.id = :usuarioId")
    Optional<Billetera> findConBloqueoPorUsuarioId(@Param("usuarioId") Long usuarioId);
}
