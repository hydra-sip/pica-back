package com.hydra.pica.plataforma_pica.user.repository;

import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.CodigoCanjeOAuth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodigoCanjeOAuthRepository extends JpaRepository<CodigoCanjeOAuth, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CodigoCanjeOAuth> findByCodigoHash(String codigoHash);

    @Modifying
    @Query("""
            delete from CodigoCanjeOAuth codigo
            where codigo.usado = true
               or codigo.venceEn < :ahora
            """)
    int borrarUsadosOVencidos(@Param("ahora") Instant ahora);
}
