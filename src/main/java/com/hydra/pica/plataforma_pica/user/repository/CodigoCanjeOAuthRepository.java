package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.CodigoCanjeOAuth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CodigoCanjeOAuthRepository extends JpaRepository<CodigoCanjeOAuth, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CodigoCanjeOAuth> findByCodigoHash(String codigoHash);
}
