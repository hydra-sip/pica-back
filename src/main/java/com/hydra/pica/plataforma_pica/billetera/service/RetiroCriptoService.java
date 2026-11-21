package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoRetiroCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.RetiroCripto;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.RetiroCriptoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.RetiroCobrado;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.Transaccion;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.RetirosCriptoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El retiro de saldo a la wallet (HU-610, PICA-455), el camino inverso a {@link CargaCriptoService}:
 * <ol>
 *   <li>{@link #solicitar}: valida los topes y el saldo como el retiro a una cuenta ({@link RetiroService}), descuenta
 *   el saldo con un movimiento RETIRO PENDIENTE y firma el permiso para PasarelaRetiros con
 *   {@link FirmanteDeRetiros}.</li>
 *   <li>El usuario cobra el permiso desde su wallet.</li>
 *   <li>{@link #confirmar}: lee el {@code RetiroCobrado} en la cadena y, con las confirmaciones, pasa el retiro a
 *   COBRADO y su movimiento a CONFIRMADO, con el hash como referencia externa (así se ve en BaseScan).</li>
 *   <li>{@link #devolverSiVencio}: un permiso vencido que el contrato no pagó devuelve el saldo.</li>
 * </ol>
 * El nodo se consulta sin ninguna transacción de base abierta, como en las cargas.
 */
@Service
@RequiredArgsConstructor
public class RetiroCriptoService {

    private static final Logger log = LoggerFactory.getLogger(RetiroCriptoService.class);
    private static final SecureRandom AZAR = new SecureRandom();

    private final RetiroCriptoRepository retiroCriptoRepository;
    private final BilleteraRepository billeteraRepository;
    private final UsuarioRepository usuarioRepository;
    private final RetiroService retiroService;
    private final FirmanteDeRetiros firmante;
    private final LectorDeCadena cadena;
    private final CriptoProperties cripto;
    private final RetirosCriptoProperties retiros;
    private final TransactionTemplate enTransaccion;
    private final AuditoriaService auditoriaService;

    @Transactional
    public RetiroCripto solicitar(Long usuarioId, BigDecimal monto, String destino) {
        exigirHabilitados();
        BigDecimal montoEscalado = retiroService.validarTopes(monto);
        String wallet = destino.toLowerCase(Locale.ROOT);
        String referencia = nuevaReferencia();

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("destino", wallet);
        detalle.put("medio", "WALLET");
        Movimiento movimiento = retiroService.descontar(usuarioId, montoEscalado, "retiro-cripto-" + referencia,
                "Retiro a la wallet " + abreviada(wallet), detalle);

        RetiroCripto retiro = new RetiroCripto();
        retiro.setReferencia(referencia);
        retiro.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        retiro.setDestino(wallet);
        retiro.setMontoPesos(montoEscalado);
        retiro.setMontoWei(cripto.weiPara(montoEscalado));
        // el contrato compara contra block.timestamp, en segundos
        retiro.setVenceEn(Instant.now().plus(retiros.vigencia()).truncatedTo(ChronoUnit.SECONDS));
        retiro.setFirma(firmante.firmar(referencia, wallet, retiro.getMontoWei(), retiro.getVenceEn().getEpochSecond()));
        retiro.setMovimiento(movimiento);
        return retiroCriptoRepository.save(retiro);
    }

    @Transactional(readOnly = true)
    public List<RetiroCripto> pendientes(Long usuarioId) {
        return retiroCriptoRepository.findByUsuarioIdAndEstadoOrderByIdDesc(usuarioId, EstadoRetiroCripto.PENDIENTE);
    }

    public RetiroCripto confirmar(Long usuarioId, String referencia, String hash) {
        String hashNormalizado = hash.toLowerCase(Locale.ROOT);
        RetiroCripto retiro = retiroCriptoRepository
                .findByReferenciaAndUsuarioId(referencia.toLowerCase(Locale.ROOT), usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RETIRO_NO_ENCONTRADO,
                        "No hay un retiro con esa referencia"));
        // un reintento del front con el retiro ya cobrado no vuelve a consultar la red
        if (retiro.getEstado() != EstadoRetiroCripto.PENDIENTE) {
            return yaResuelto(retiro, hashNormalizado);
        }
        if (retiroCriptoRepository.existsByHash(hashNormalizado)) {
            throw rechazo(retiro, hashNormalizado, CodigoError.TRANSACCION_YA_USADA,
                    "Esa transacción ya cobró otro retiro");
        }

        Transaccion.Minada minada = switch (cadena.transaccion(hashNormalizado)) {
            case Transaccion.Inexistente inexistente -> throw rechazo(retiro, hashNormalizado,
                    CodigoError.TRANSACCION_INEXISTENTE, "La red no tiene una transacción con ese hash");
            case Transaccion.Pendiente pendiente -> throw pendiente();
            case Transaccion.Minada m -> m;
        };
        if (!minada.exitosa()) {
            throw rechazo(retiro, hashNormalizado, CodigoError.TRANSACCION_FALLIDA,
                    "La transacción revirtió en la cadena: no se cobró el retiro");
        }
        if (cadena.bloqueActual() - minada.bloque() + 1 < cripto.confirmacionesMinimas()) {
            throw pendiente();
        }
        // el contrato solo emite el evento si la firma era para esa wallet y ese monto, pero se compara igual: el
        // back no confía en nada que no haya leído él
        RetiroCobrado cobro = minada.retiros().stream()
                .filter(r -> r.contrato().equalsIgnoreCase(retiros.contrato())
                        && r.referencia().equals(retiro.getReferencia()))
                .findFirst()
                .orElseThrow(() -> rechazo(retiro, hashNormalizado, CodigoError.TRANSACCION_SIN_RETIRO,
                        "La transacción no cobró este retiro en el contrato de la pasarela"));
        if (!cobro.destino().equals(retiro.getDestino()) || !cobro.monto().equals(retiro.getMontoWei())) {
            throw rechazo(retiro, hashNormalizado, CodigoError.TRANSACCION_SIN_RETIRO,
                    "La transacción cobró el retiro con otra wallet u otro monto");
        }

        try {
            return enTransaccion.execute(estado -> marcarCobrado(retiro.getId(), hashNormalizado));
        } catch (DataIntegrityViolationException e) {
            throw rechazo(retiro, hashNormalizado, CodigoError.TRANSACCION_YA_USADA,
                    "Esa transacción ya cobró otro retiro");
        }
    }

    /**
     * Revisa un retiro vencido: si el contrato dice que se cobró (el usuario no volvió a avisar), queda COBRADO; si
     * no, se devuelve el saldo. Lo llama {@link DevolucionRetirosCripto}, uno por uno.
     *
     * @return el estado en que quedó
     */
    public EstadoRetiroCripto devolverSiVencio(Long retiroId) {
        RetiroCripto retiro = retiroCriptoRepository.findById(retiroId).orElseThrow();
        if (retiro.getEstado() != EstadoRetiroCripto.PENDIENTE) {
            return retiro.getEstado();
        }
        // pasado el vencimiento el contrato ya no lo paga: lo que diga ahora no cambia
        boolean cobrado = cadena.retiroCobrado(retiros.contrato(), retiro.getReferencia());
        return enTransaccion.execute(estado -> cobrado
                ? marcarCobrado(retiroId, null).getEstado()
                : devolver(retiroId).getEstado());
    }

    private RetiroCripto marcarCobrado(Long retiroId, String hash) {
        RetiroCripto retiro = retiroCriptoRepository.findConBloqueoPorId(retiroId).orElseThrow();
        // otra confirmación o la devolución lo pudieron resolver mientras se consultaba la red
        if (retiro.getEstado() != EstadoRetiroCripto.PENDIENTE) {
            return hash == null ? retiro : yaResuelto(retiro, hash);
        }
        retiro.setEstado(EstadoRetiroCripto.COBRADO);
        retiro.setHash(hash);
        Movimiento movimiento = retiro.getMovimiento();
        movimiento.setEstado(EstadoMovimiento.CONFIRMADO);
        if (hash != null) {
            movimiento.setReferenciaExterna(hash);
        }
        retiroCriptoRepository.saveAndFlush(retiro);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("referencia", retiro.getReferencia());
        detalle.put("destino", retiro.getDestino());
        detalle.put("montoWei", retiro.getMontoWei().toString());
        detalle.put("hash", hash);
        detalle.put("estado", EstadoRetiroCripto.COBRADO.name());
        auditoriaService.registrar(retiro.getUsuario().getId(), AccionAuditoria.MODIFICACION, "retiro",
                String.valueOf(movimiento.getId()), detalle);
        return retiro;
    }

    private RetiroCripto devolver(Long retiroId) {
        RetiroCripto retiro = retiroCriptoRepository.findConBloqueoPorId(retiroId).orElseThrow();
        if (retiro.getEstado() != EstadoRetiroCripto.PENDIENTE) {
            return retiro;
        }
        Long usuarioId = retiro.getUsuario().getId();
        Billetera billetera = billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElseThrow();
        billetera.setSaldoDisponible(billetera.getSaldoDisponible().add(retiro.getMontoPesos()));
        // el RETIRO rechazado ya no descuenta: el saldo vuelve sin otro movimiento (ControlDeBalance)
        retiro.getMovimiento().setEstado(EstadoMovimiento.RECHAZADO);
        retiro.setEstado(EstadoRetiroCripto.DEVUELTO);
        retiroCriptoRepository.saveAndFlush(retiro);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("referencia", retiro.getReferencia());
        detalle.put("monto", retiro.getMontoPesos());
        detalle.put("venceEn", retiro.getVenceEn().toString());
        detalle.put("saldoDisponible", billetera.getSaldoDisponible());
        detalle.put("estado", EstadoRetiroCripto.DEVUELTO.name());
        auditoriaService.registrar(usuarioId, AccionAuditoria.MODIFICACION, "retiro",
                String.valueOf(retiro.getMovimiento().getId()), detalle);
        log.info("Retiro a la wallet {} vencido sin cobrar: se devolvieron {} al usuario {}",
                retiro.getReferencia(), retiro.getMontoPesos(), usuarioId);
        return retiro;
    }

    private static RetiroCripto yaResuelto(RetiroCripto retiro, String hash) {
        if (retiro.getEstado() == EstadoRetiroCripto.DEVUELTO) {
            throw new ConflictoException(CodigoError.RETIRO_VENCIDO,
                    "El permiso venció sin cobrarse y el saldo volvió a la billetera");
        }
        if (retiro.getHash() != null && !retiro.getHash().equals(hash)) {
            throw new ConflictoException(CodigoError.RETIRO_YA_COBRADO,
                    "El retiro ya se cobró con otra transacción");
        }
        return retiro;
    }

    private void exigirHabilitados() {
        if (!firmante.habilitado()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, CodigoError.RETIROS_CRIPTO_NO_DISPONIBLES,
                    "Los retiros a la wallet no están disponibles en este momento");
        }
    }

    private static ConflictoException pendiente() {
        return new ConflictoException(CodigoError.TRANSACCION_PENDIENTE,
                "La transacción todavía no tiene las confirmaciones necesarias. Probá de nuevo en unos segundos");
    }

    /** Un 400 que deja rastro en la auditoría, en su propia transacción. El retiro sigue pendiente. */
    private ApiException rechazo(RetiroCripto retiro, String hash, CodigoError codigo, String motivo) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("referencia", retiro.getReferencia());
        detalle.put("hash", hash);
        detalle.put("motivo", motivo);
        detalle.put("estado", "RECHAZADA");
        auditoriaService.registrarEnTransaccionNueva(retiro.getUsuario().getId(), AccionAuditoria.MODIFICACION,
                "RETIRO_CRIPTO", retiro.getId().toString(), detalle);
        return new ApiException(HttpStatus.BAD_REQUEST, codigo, motivo);
    }

    private static String abreviada(String wallet) {
        return wallet.substring(0, 6) + "…" + wallet.substring(wallet.length() - 4);
    }

    // el bytes32 del permiso: 32 bytes al azar, que nadie puede adivinar
    private static String nuevaReferencia() {
        byte[] bytes = new byte[32];
        AZAR.nextBytes(bytes);
        return "0x" + HexFormat.of().formatHex(bytes);
    }
}
