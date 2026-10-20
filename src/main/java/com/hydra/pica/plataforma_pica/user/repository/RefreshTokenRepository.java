package com.hydra.pica.plataforma_pica.user.repository;

import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.RefreshToken;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            update RefreshToken token
            set token.revocadoEn = :ahora
            where token.usuario.id = :usuarioId
              and token.revocadoEn is null
            """)
    int revocarActivosPorUsuario(@Param("usuarioId") Long usuarioId, @Param("ahora") Instant ahora);

    @Modifying
    @Query("""
            update RefreshToken token
            set token.revocadoEn = :ahora
            where token.usuario.id = :usuarioId
              and token.revocadoEn is null
            """)
    int revocarActivosPorFamilia(@Param("usuarioId") Long usuarioId, @Param("ahora") Instant ahora);
}
