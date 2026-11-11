package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCargaCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.CargaCriptoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.CargaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.Transaccion;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
    private final TransactionTemplate enTransaccion;
    private final AuditoriaService auditoriaService;

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
        // un reintento del front con la carga ya acreditada no vuelve a consultar la red
        if (carga.getEstado() == EstadoCargaCripto.ACREDITADA) {
            return enTransaccion.execute(estado ->
                    yaAcreditada(cargaCriptoRepository.findById(carga.getId()).orElseThrow(), hashNormalizado));
        }
        if (cargaCriptoRepository.existsByHash(hashNormalizado)) {
            throw yaUsada(carga, hashNormalizado);
        }

        Transaccion.Minada minada = switch (cadena.transaccion(hashNormalizado)) {
            case Transaccion.Inexistente inexistente -> throw rechazo(carga, hashNormalizado,
                    CodigoError.TRANSACCION_INEXISTENTE, "La red no tiene una transacción con ese hash");
            case Transaccion.Pendiente pendiente -> throw pendiente();
            case Transaccion.Minada m -> m;
        };
        if (!minada.exitosa()) {
            throw rechazo(carga, hashNormalizado, CodigoError.TRANSACCION_FALLIDA,
                    "La transacción revirtió en la cadena: no se cobró la carga");
        }
        // contando el bloque de la transacción
        if (cadena.bloqueActual() - minada.bloque() + 1 < cripto.confirmacionesMinimas()) {
            throw pendiente();
        }
        // el to de la transacción no importa (una smart wallet paga a través de otra dirección): un log con la
        // dirección del contrato solo lo puede emitir el contrato
        CargaRegistrada pago = minada.cargas().stream()
                .filter(c -> c.contrato().equalsIgnoreCase(cripto.contrato())
                        && c.referencia().equals(carga.getReferencia()))
                .findFirst()
                .orElseThrow(() -> rechazo(carga, hashNormalizado, CodigoError.TRANSACCION_SIN_CARGA,
                        "La transacción no pagó esta carga en el contrato de la pasarela"));
        if (!pago.monto().equals(carga.getMontoWei())) {
            throw rechazo(carga, hashNormalizado, CodigoError.MONTO_NO_COINCIDE,
                    "La transacción pagó " + pago.monto() + " wei y la carga es de " + carga.getMontoWei());
        }

        try {
            return enTransaccion.execute(estado -> acreditar(carga.getId(), usuarioId, hashNormalizado, pago));
        } catch (DataIntegrityViolationException e) {
            // otra carga se acreditó con el mismo hash al mismo tiempo: la constraint única de hash la frena
            throw yaUsada(carga, hashNormalizado);
        }
    }

    /**
     * Una transacción acredita una sola carga: el movimiento lleva el hash como referencia externa. Si pagó más de
     * una (una smart wallet que agrupa llamadas), lo demás se devuelve a mano desde el contrato.
     */
    private ApiException yaUsada(CargaCripto carga, String hash) {
        return rechazo(carga, hash, CodigoError.TRANSACCION_YA_USADA,
                "Esa transacción ya acreditó otra carga");
    }

    // el contrato no deja pagar dos veces una referencia: otro hash no puede ser otro pago de esta carga
    private static Movimiento yaAcreditada(CargaCripto carga, String hash) {
        if (!hash.equals(carga.getHash())) {
            throw new ConflictoException(CodigoError.CARGA_YA_ACREDITADA,
                    "La carga ya se acreditó con otra transacción");
        }
        return (Movimiento) Hibernate.unproxy(carga.getMovimiento());
    }

    private static ConflictoException pendiente() {
        return new ConflictoException(CodigoError.TRANSACCION_PENDIENTE,
                "La transacción todavía no tiene las confirmaciones necesarias. Probá de nuevo en unos segundos");
    }

    /**
     * Un 400 que deja rastro en la auditoría, en su propia transacción. La carga sigue pendiente: todavía la puede
     * pagar otra transacción.
     */
    private ApiException rechazo(CargaCripto carga, String hash, CodigoError codigo, String motivo) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("monto", carga.getMontoPesos());
        detalle.put("referencia", carga.getReferencia());
        detalle.put("hash", hash);
        detalle.put("motivo", motivo);
        detalle.put("estado", "RECHAZADA");
        auditoriaService.registrarEnTransaccionNueva(carga.getUsuario().getId(), AccionAuditoria.PAGO,
                "CARGA_CRIPTO", carga.getId().toString(), detalle);
        return new ApiException(HttpStatus.BAD_REQUEST, codigo, motivo);
    }

    private Movimiento acreditar(Long cargaId, Long usuarioId, String hash, CargaRegistrada pago) {
        CargaCripto carga = cargaCriptoRepository.findConBloqueoPorId(cargaId).orElseThrow();
        // otra confirmación la pudo acreditar mientras se consultaba la red
        if (carga.getEstado() == EstadoCargaCripto.ACREDITADA) {
            return yaAcreditada(carga, hash);
        }
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
