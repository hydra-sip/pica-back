package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import com.hydra.pica.plataforma_pica.billetera.repository.CargaCriptoRepository;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La carga de saldo pagada con la wallet (HU-609, PICA-333): al iniciarla, el usuario recibe una referencia al azar y
 * los wei que tiene que pagar con ella en el contrato, con la cotización fija de {@link CriptoProperties}.
 */
@Service
@RequiredArgsConstructor
public class CargaCriptoService {

    private static final SecureRandom AZAR = new SecureRandom();

    private final CargaCriptoRepository cargaCriptoRepository;
    private final UsuarioRepository usuarioRepository;
    private final CriptoProperties cripto;

    @Transactional
    public CargaCripto iniciar(Long usuarioId, BigDecimal monto) {
        CargaCripto carga = new CargaCripto();
        carga.setReferencia(nuevaReferencia());
        carga.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        carga.setMontoPesos(monto);
        carga.setMontoWei(cripto.weiPara(monto));
        return cargaCriptoRepository.save(carga);
    }

    // el bytes32 que se paga en el contrato: 32 bytes al azar, que nadie puede adivinar
    private static String nuevaReferencia() {
        byte[] bytes = new byte[32];
        AZAR.nextBytes(bytes);
        return "0x" + HexFormat.of().formatHex(bytes);
    }
}
