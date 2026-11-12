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
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio encargado de gestionar los retiros de saldo disponible hacia cuentas bancarias validadas (HU-607, PICA-337).
 * Controla topes configurados en la plataforma, bloquea pesimistamente la billetera para evitar saldos negativos
 * en solicitudes concurrentes, descuenta saldo disponible y registra el movimiento en estado PENDIENTE.
 */
@Service
@RequiredArgsConstructor
public class RetiroService {

    private static final Logger log = LoggerFactory.getLogger(RetiroService.class);
    public static final String MENSAJE_SALDO_INSUFICIENTE =
            "El saldo disponible no alcanza para el retiro solicitado. Los fondos retenidos no pueden ser movilizados";

    private final BilleteraRepository billeteraRepository;
    private final CuentaBancariaRepository cuentaBancariaRepository;
    private final MovimientoRepository movimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ParametrosPlataforma parametrosPlataforma;
    private final AuditoriaService auditoriaService;

    @Transactional
    public Movimiento solicitar(Long usuarioId, Long cuentaId, BigDecimal monto) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "El monto debe ser mayor a cero");
        }
        if (cuentaId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "La cuenta es obligatoria");
        }

        // 1. Validar topes mínimo y máximo de la plataforma
        BigDecimal minimo = parametrosPlataforma.retiroMinimo();
        BigDecimal maximo = parametrosPlataforma.retiroMaximo();
        BigDecimal montoEscalado = monto.setScale(2, RoundingMode.HALF_UP);

        if (montoEscalado.compareTo(minimo) < 0 || montoEscalado.compareTo(maximo) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.RETIRO_FUERA_DE_TOPES,
                    "El monto a retirar debe estar entre $" + minimo.stripTrailingZeros().toPlainString()
                            + " y $" + maximo.stripTrailingZeros().toPlainString())
                    .con("minimo", minimo)
                    .con("maximo", maximo)
                    .con("monto", montoEscalado);
        }

        // 2. Validar que la cuenta pertenezca al usuario y esté VALIDADA
        CuentaBancaria cuenta = cuentaBancariaRepository.findByIdAndUsuarioId(cuentaId, usuarioId)
                .orElseThrow(() -> new NoEncontradoException(
                        CodigoError.CUENTA_BANCARIA_NO_ENCONTRADA,
                        "Cuenta bancaria no encontrada o no pertenece al usuario"));

        if (cuenta.getEstadoValidacion() != EstadoValidacion.VALIDADA) {
            throw new ConflictoException(CodigoError.CUENTA_NO_VALIDADA,
                    "Solo se puede solicitar retiros a una cuenta bancaria validada");
        }

        // 3. Bloqueo pesimista de la billetera (SELECT ... FOR UPDATE)
        Billetera billetera = billeteraRepository.findConBloqueoPorUsuarioId(usuarioId)
                .orElseGet(() -> {
                    Billetera nueva = new Billetera();
                    nueva.setUsuario(usuarioRepository.getReferenceById(usuarioId));
                    nueva.setSaldoDisponible(BigDecimal.ZERO);
                    nueva.setSaldoRetenido(BigDecimal.ZERO);
                    nueva.setGananciaAcumulada(BigDecimal.ZERO);
                    nueva.setMoneda("ARS");
                    nueva.setEstado(EstadoBilletera.ACTIVA);
                    billeteraRepository.save(nueva);
                    return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElse(nueva);
                });

        if (billetera.getEstado() == EstadoBilletera.BLOQUEADA) {
            throw new ProhibidoException(CodigoError.BILLETERA_BLOQUEADA, "La billetera se encuentra bloqueada");
        }

        // 4. Validar saldo disponible
        if (montoEscalado.compareTo(billetera.getSaldoDisponible()) > 0) {
            throw new ConflictoException(CodigoError.SALDO_INSUFICIENTE, MENSAJE_SALDO_INSUFICIENTE)
                    .con("saldoDisponible", billetera.getSaldoDisponible())
                    .con("saldoRetenido", billetera.getSaldoRetenido())
                    .con("montoSolicitado", montoEscalado);
        }

        // 5. Deducir saldo disponible
        BigDecimal nuevoSaldoDisponible = billetera.getSaldoDisponible().subtract(montoEscalado);
        billetera.setSaldoDisponible(nuevoSaldoDisponible);
        billeteraRepository.save(billetera);

        // 6. Registrar movimiento RETIRO en estado PENDIENTE
        String referencia = "sim-retiro-" + UUID.randomUUID();
        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        movimiento.setTipo(TipoMovimiento.RETIRO);
        movimiento.setMonto(montoEscalado);
        movimiento.setMoneda(billetera.getMoneda() != null ? billetera.getMoneda() : "ARS");
        movimiento.setSaldoResultante(nuevoSaldoDisponible);
        movimiento.setEstado(EstadoMovimiento.PENDIENTE);
        movimiento.setReferenciaExterna(referencia);
        movimiento.setDescripcion("Retiro a " + cuenta.getTipo() + " " + cuenta.getValor());
        Movimiento guardado = movimientoRepository.saveAndFlush(movimiento);

        // 7. Auditoría
        Map<String, Object> detalleAuditoria = new LinkedHashMap<>();
        detalleAuditoria.put("monto", montoEscalado);
        detalleAuditoria.put("cuentaId", cuenta.getId());
        detalleAuditoria.put("tipoCuenta", cuenta.getTipo().name());
        detalleAuditoria.put("valorCuenta", cuenta.getValor());
        detalleAuditoria.put("saldoResultante", nuevoSaldoDisponible);
        detalleAuditoria.put("referencia", referencia);
        detalleAuditoria.put("estado", EstadoMovimiento.PENDIENTE.name());

        auditoriaService.registrar(
                usuarioId,
                AccionAuditoria.ALTA,
                "retiro",
                String.valueOf(guardado.getId()),
                detalleAuditoria);

        log.info("Retiro solicitado por usuario {}: monto {}, cuenta {}, saldo restante {}",
                usuarioId, montoEscalado, cuenta.getId(), nuevoSaldoDisponible);

        return guardado;
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
                "retiro",
                String.valueOf(guardado.getId()),
                Map.of("estado", EstadoMovimiento.CONFIRMADO.name(), "referencia", movimiento.getReferenciaExterna()));

        return guardado;
    }
}
