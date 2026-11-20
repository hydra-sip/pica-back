package com.hydra.pica.plataforma_pica.notificacion.repository;

import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.notificacion.domain.Notificacion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificacionRepository extends JpaRepository<Notificacion, Long> {

    Page<Notificacion> findByUsuarioIdOrderByCreadaEnDescIdDesc(Long usuarioId, Pageable pageable);

    Page<Notificacion> findByUsuarioIdAndLeidaEnIsNullOrderByCreadaEnDescIdDesc(Long usuarioId, Pageable pageable);

    Page<Notificacion> findByUsuarioIdAndLeidaEnIsNotNullOrderByCreadaEnDescIdDesc(Long usuarioId, Pageable pageable);

    Optional<Notificacion> findByIdAndUsuarioId(Long id, Long usuarioId);

    @Modifying
    @Query("UPDATE Notificacion n SET n.leidaEn = :ahora WHERE n.usuarioId = :usuarioId AND n.leidaEn IS NULL")
    int marcarTodasLeidas(@Param("usuarioId") Long usuarioId, @Param("ahora") Instant ahora);
}
