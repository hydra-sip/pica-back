package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCargaCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.CargaCriptoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.CargaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.Transaccion;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La carga de saldo pagada con la wallet (HU-609, PICA-333): al iniciarla, el usuario recibe una referencia al azar y
 * los wei que tiene que pagar con ella en el contrato, con la cotización fija de {@link CriptoProperties}. Al
 * confirmarla con el hash, se lee la transacción en la cadena y, si pagó esa carga, se acredita con
 * {@link CargaService} usando el hash como referencia externa. El nodo se consulta sin ninguna transacción de base
 * abierta: recién para acreditar se bloquea la carga.
 */
@Service
@RequiredArgsConstructor
public class CargaCriptoService {

    private static final SecureRandom AZAR = new SecureRandom();

    private final CargaCriptoRepository cargaCriptoRepository;
    private final UsuarioRepository usuarioRepository;
    private final CriptoProperties cripto;
    private final LectorDeCadena cadena;
    private final CargaService cargaService;
    private final TransactionTemplate transaccion;

    @Transactional
    public CargaCripto iniciar(Long usuarioId, BigDecimal monto) {
        CargaCripto carga = new CargaCripto();
        carga.setReferencia(nuevaReferencia());
        carga.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        carga.setMontoPesos(monto);
        carga.setMontoWei(cripto.weiPara(monto));
        return cargaCriptoRepository.save(carga);
    }

    public Movimiento confirmar(Long usuarioId, String referencia, String hash) {
        String hashNormalizado = hash.toLowerCase(Locale.ROOT);
        CargaCripto carga = cargaCriptoRepository
                .findByReferenciaAndUsuarioId(referencia.toLowerCase(Locale.ROOT), usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.CARGA_NO_ENCONTRADA,
                        "No hay una carga con esa referencia"));

        Transaccion.Minada minada = (Transaccion.Minada) cadena.transaccion(hashNormalizado);
        CargaRegistrada pago = minada.cargas().stream()
                .filter(c -> c.contrato().equalsIgnoreCase(cripto.contrato())
                        && c.referencia().equals(carga.getReferencia()))
                .findFirst()
                .orElseThrow();

        return transaccion.execute(estado -> acreditar(carga.getId(), usuarioId, hashNormalizado, pago));
    }

    private Movimiento acreditar(Long cargaId, Long usuarioId, String hash, CargaRegistrada pago) {
        CargaCripto carga = cargaCriptoRepository.findConBloqueoPorId(cargaId).orElseThrow();
        Movimiento movimiento = cargaService.acreditar(usuarioId, carga.getMontoPesos(), hash, "Carga con cripto");
        carga.setEstado(EstadoCargaCripto.ACREDITADA);
        carga.setHash(hash);
        carga.setPagador(pago.pagador());
        carga.setMovimiento(movimiento);
        return movimiento;
    }

    // el bytes32 que se paga en el contrato: 32 bytes al azar, que nadie puede adivinar
    private static String nuevaReferencia() {
        byte[] bytes = new byte[32];
        AZAR.nextBytes(bytes);
        return "0x" + HexFormat.of().formatHex(bytes);
    }
}
