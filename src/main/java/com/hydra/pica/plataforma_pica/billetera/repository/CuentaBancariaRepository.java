package com.hydra.pica.plataforma_pica.billetera.repository;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CuentaBancariaRepository extends JpaRepository<CuentaBancaria, Long> {

    List<CuentaBancaria> findByUsuarioIdOrderByCreadoEnDescIdDesc(Long usuarioId);

    Optional<CuentaBancaria> findByIdAndUsuarioId(Long id, Long usuarioId);

    Optional<CuentaBancaria> findByUsuarioIdAndValorIgnoreCase(Long usuarioId, String valor);
}
