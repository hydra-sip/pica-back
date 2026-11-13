package com.hydra.pica.plataforma_pica.billetera.repository;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CargaCriptoRepository extends JpaRepository<CargaCripto, Long> {

    Optional<CargaCripto> findByReferenciaAndUsuarioId(String referencia, Long usuarioId);

    /** Para la conciliación (HU-608): todas, con quién las inició y su movimiento si se acreditaron. */
    @Query("select c from CargaCripto c join fetch c.usuario left join fetch c.movimiento")
    List<CargaCripto> todasConUsuarioYMovimiento();

    boolean existsByHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CargaCripto c WHERE c.id = :id")
    Optional<CargaCripto> findConBloqueoPorId(@Param("id") Long id);
}
