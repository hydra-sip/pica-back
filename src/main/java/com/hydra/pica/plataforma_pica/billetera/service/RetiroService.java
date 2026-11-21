package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.LimitesRetiroResponse;
import com.hydra.pica.plataforma_pica.billetera.event.MovimientoBilleteraRegistrado;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.EntidadAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio encargado de gestionar los retiros de saldo disponible hacia cuentas
 * bancarias validadas (HU-607, PICA-337).
 * Controla topes configurados en la plataforma, bloquea pesimistamente la
 * billetera para evitar saldos negativos
 * en solicitudes concurrentes, descuenta saldo disponible y registra el
 * movimiento en estado PENDIENTE.
 */
@Service
@RequiredArgsConstructor
public class RetiroService {

    private static final Logger log = LoggerFactory.getLogger(RetiroService.class);
    private static final String MONEDA_POR_DEFECTO = "ARS";
    private static final int ESCALA_MONTO = 2;
    public static final String MENSAJE_SALDO_INSUFICIENTE = "El saldo disponible no alcanza para el retiro solicitado. Los fondos retenidos no pueden ser movilizados";

    private final BilleteraRepository billeteraRepository;
    private final CuentaBancariaRepository cuentaBancariaRepository;
    private final MovimientoRepository movimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ParametrosPlataforma parametrosPlataforma;
    private final AuditoriaService auditoriaService;
    private final ApplicationEventPublisher eventos;

    public LimitesRetiroResponse obtenerLimites() {
        return new LimitesRetiroResponse(
                parametrosPlataforma.retiroMinimo(),
                parametrosPlataforma.retiroMaximo());
    }

    @Transactional
    public Movimiento solicitar(Long usuarioId, Long cuentaId, BigDecimal monto) {
        validarEntrada(cuentaId, monto);
        BigDecimal montoEscalado = validarTopes(monto);
        CuentaBancaria cuenta = obtenerCuentaValidada(usuarioId, cuentaId);

        Map<String, Object> detalleCuenta = new LinkedHashMap<>();
        detalleCuenta.put("cuentaId", cuenta.getId());
        detalleCuenta.put("tipoCuenta", cuenta.getTipo().name());
        detalleCuenta.put("valorCuenta", cuenta.getValor());
        Movimiento guardado = descontar(usuarioId, montoEscalado, "sim-retiro-" + UUID.randomUUID(),
                "Retiro a " + cuenta.getTipo() + " " + cuenta.getValor(), detalleCuenta);

        log.info("Retiro solicitado por usuario {}: monto {}, cuenta {}, saldo restante {}",
                usuarioId, montoEscalado, cuenta.getId(), guardado.getSaldoResultante());

        return guardado;
    }

    /**
     * Valida el monto contra los topes de la plataforma (HU-702) y lo devuelve con 2 decimales. Lo usan el retiro a una
     * cuenta y el retiro a la wallet (HU-610).
     */
    public BigDecimal validarTopes(BigDecimal monto) {
        BigDecimal minimo = parametrosPlataforma.retiroMinimo();
        BigDecimal maximo = parametrosPlataforma.retiroMaximo();
        BigDecimal montoEscalado = monto.setScale(ESCALA_MONTO, RoundingMode.HALF_UP);

        if (montoEscalado.compareTo(minimo) < 0 || montoEscalado.compareTo(maximo) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.RETIRO_FUERA_DE_TOPES,
                    "El monto a retirar debe estar entre $" + minimo.stripTrailingZeros().toPlainString()
                            + " y $" + maximo.stripTrailingZeros().toPlainString())
                    .con("minimo", minimo)
                    .con("maximo", maximo)
                    .con("monto", montoEscalado);
        }
        return montoEscalado;
    }

    /**
     * Descuenta {@code monto} del disponible con la billetera bloqueada y registra el movimiento RETIRO PENDIENTE, con
     * su auditoría y el evento para la detección de fraude. Lo usan el retiro a una cuenta y el retiro a la wallet
     * (HU-610). Tiene que correr dentro de una transacción.
     */
    public Movimiento descontar(Long usuarioId, BigDecimal monto, String referencia, String descripcion,
                                Map<String, Object> detalle) {
        Billetera billetera = obtenerOCrearBilleteraBloqueada(usuarioId);
        validarBilleteraYSaldo(billetera, monto);

        BigDecimal nuevoSaldoDisponible = billetera.getSaldoDisponible().subtract(monto);
        billetera.setSaldoDisponible(nuevoSaldoDisponible);
        billeteraRepository.save(billetera);

        Movimiento guardado = registrarMovimientoRetiro(billetera, monto, nuevoSaldoDisponible, referencia,
                descripcion);
        auditarSolicitud(usuarioId, guardado, detalle);

        if (eventos != null) {
            eventos.publishEvent(new MovimientoBilleteraRegistrado(
                    guardado.getId(), usuarioId, TipoMovimiento.RETIRO, monto));
        }
        return guardado;
    }

    private static void validarEntrada(Long cuentaId, BigDecimal monto) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "El monto debe ser mayor a cero");
        }
        if (cuentaId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "La cuenta es obligatoria");
        }
    }

    private CuentaBancaria obtenerCuentaValidada(Long usuarioId, Long cuentaId) {
        CuentaBancaria cuenta = cuentaBancariaRepository.findByIdAndUsuarioId(cuentaId, usuarioId)
                .orElseThrow(() -> new NoEncontradoException(
                        CodigoError.CUENTA_BANCARIA_NO_ENCONTRADA,
                        "Cuenta bancaria no encontrada o no pertenece al usuario"));

        if (cuenta.getEstadoValidacion() != EstadoValidacion.VALIDADA) {
            throw new ConflictoException(CodigoError.CUENTA_NO_VALIDADA,
                    "Solo se puede solicitar retiros a una cuenta bancaria validada");
        }
        return cuenta;
    }

    private Billetera obtenerOCrearBilleteraBloqueada(Long usuarioId) {
        // bloqueo pesimista de la billetera (SELECT ... FOR UPDATE)
        return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId)
                .orElseGet(() -> {
                    Billetera nueva = new Billetera();
                    nueva.setUsuario(usuarioRepository.getReferenceById(usuarioId));
                    nueva.setSaldoDisponible(BigDecimal.ZERO);
                    nueva.setSaldoRetenido(BigDecimal.ZERO);
                    nueva.setGananciaAcumulada(BigDecimal.ZERO);
                    nueva.setMoneda(MONEDA_POR_DEFECTO);
                    nueva.setEstado(EstadoBilletera.ACTIVA);
                    billeteraRepository.save(nueva);
                    return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElse(nueva);
                });
    }

    private static void validarBilleteraYSaldo(Billetera billetera, BigDecimal monto) {
        if (billetera.getEstado() == EstadoBilletera.BLOQUEADA) {
            throw new ProhibidoException(CodigoError.BILLETERA_BLOQUEADA, "La billetera se encuentra bloqueada");
        }
        if (monto.compareTo(billetera.getSaldoDisponible()) > 0) {
            throw new ConflictoException(CodigoError.SALDO_INSUFICIENTE, MENSAJE_SALDO_INSUFICIENTE)
                    .con("saldoDisponible", billetera.getSaldoDisponible())
                    .con("saldoRetenido", billetera.getSaldoRetenido())
                    .con("montoSolicitado", monto);
        }
    }

    private Movimiento registrarMovimientoRetiro(Billetera billetera, BigDecimal monto, BigDecimal nuevoSaldoDisponible,
                                                 String referencia, String descripcion) {
        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        movimiento.setTipo(TipoMovimiento.RETIRO);
        movimiento.setMonto(monto);
        movimiento.setMoneda(billetera.getMoneda() != null ? billetera.getMoneda() : MONEDA_POR_DEFECTO);
        movimiento.setSaldoResultante(nuevoSaldoDisponible);
        movimiento.setEstado(EstadoMovimiento.PENDIENTE);
        movimiento.setReferenciaExterna(referencia);
        movimiento.setDescripcion(descripcion);
        return movimientoRepository.saveAndFlush(movimiento);
    }

    private void auditarSolicitud(Long usuarioId, Movimiento guardado, Map<String, Object> detalle) {
        Map<String, Object> detalleAuditoria = new LinkedHashMap<>();
        detalleAuditoria.put("monto", guardado.getMonto());
        detalleAuditoria.putAll(detalle);
        detalleAuditoria.put("saldoResultante", guardado.getSaldoResultante());
        detalleAuditoria.put("referencia", guardado.getReferenciaExterna());
        detalleAuditoria.put("estado", EstadoMovimiento.PENDIENTE.name());

        auditoriaService.registrar(
                usuarioId,
                AccionAuditoria.ALTA,
                EntidadAuditoria.RETIRO,
                String.valueOf(guardado.getId()),
                detalleAuditoria);
    }

    /**
     * Confirma la transferencia simulada de un retiro en proceso (PICA-337).
     */
    @Transactional
    public Movimiento confirmarTransferencia(Long movimientoId) {
        Movimiento movimiento = movimientoRepository.findById(movimientoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.ERROR_INTERNO, "Movimiento no encontrado"));
        if (movimiento.getTipo() != TipoMovimiento.RETIRO) {
            throw new ConflictoException(CodigoError.VALIDACION, "El movimiento no corresponde a un retiro");
        }
        movimiento.setEstado(EstadoMovimiento.CONFIRMADO);
        Movimiento guardado = movimientoRepository.saveAndFlush(movimiento);

        auditoriaService.registrar(
                movimiento.getBilletera().getUsuario().getId(),
                AccionAuditoria.MODIFICACION,
                EntidadAuditoria.RETIRO,
                String.valueOf(guardado.getId()),
                Map.of("estado", EstadoMovimiento.CONFIRMADO.name(), "referencia", movimiento.getReferenciaExterna()));

        return guardado;
    }
}
