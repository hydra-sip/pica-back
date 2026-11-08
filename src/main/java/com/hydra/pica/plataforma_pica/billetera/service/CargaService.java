package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.domain.Auditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio encargado de acreditar fondos en la billetera virtual del usuario (HU-602, T602-2).
 * Es idempotente por referencia externa y concurrente-seguro mediante bloqueo pesimista.
 * Compatible con la pasarela blockchain de Base Sepolia (T609-3 / PICA-333).
 */
@Service
@RequiredArgsConstructor
public class CargaService {

    private static final Logger log = LoggerFactory.getLogger(CargaService.class);

    private final BilleteraRepository billeteraRepository;
    private final MovimientoRepository movimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;

    /**
     * Acredita un monto en la billetera del usuario especificado de forma idempotente.
     *
     * @param usuarioId   identificador del usuario dueño de la billetera
     * @param monto       monto a acreditar (debe ser mayor a 0)
     * @param referencia  código único de la transacción (sim-carga-... o txHash de blockchain)
     * @param descripcion descripción del movimiento (opcional)
     * @return el movimiento de carga registrado o el existente si ya fue acreditado
     */
    @Transactional
    public Movimiento acreditar(Long usuarioId, BigDecimal monto, String referencia, String descripcion) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "El monto debe ser mayor a cero");
        }
        if (referencia == null || referencia.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "La referencia es obligatoria");
        }

        // 1. Verificación previa de idempotencia (camino rápido)
        Optional<Movimiento> existentePrevio = movimientoRepository.findByReferenciaExterna(referencia);
        if (existentePrevio.isPresent()) {
            log.info("Carga ya procesada previamente para referencia {}: retornando movimiento {}", referencia, existentePrevio.get().getId());
            return validarYDevolverExistente(existentePrevio.get(), usuarioId);
        }

        // 2. Bloqueo pesimista de la billetera del usuario (SELECT ... FOR UPDATE)
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

        // 3. Verificación de idempotencia tras adquirir el bloqueo (doble verificación concurrente)
        Optional<Movimiento> existenteConBloqueo = movimientoRepository.findByReferenciaExterna(referencia);
        if (existenteConBloqueo.isPresent()) {
            log.info("Carga procesada por llamada concurrente para referencia {}: retornando movimiento {}", referencia, existenteConBloqueo.get().getId());
            return validarYDevolverExistente(existenteConBloqueo.get(), usuarioId);
        }

        // 4. Verificación de estado de la billetera
        if (billetera.getEstado() == EstadoBilletera.BLOQUEADA) {
            throw new ProhibidoException(CodigoError.BILLETERA_BLOQUEADA, "La billetera se encuentra bloqueada");
        }

        // 5. Actualización de saldo disponible
        BigDecimal montoAcreditado = monto.setScale(2, RoundingMode.HALF_UP);
        BigDecimal nuevoSaldo = billetera.getSaldoDisponible().add(montoAcreditado);
        billetera.setSaldoDisponible(nuevoSaldo);
        billeteraRepository.save(billetera);

        // 6. Registro del movimiento
        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        movimiento.setTipo(TipoMovimiento.CARGA);
        movimiento.setMonto(montoAcreditado);
        movimiento.setMoneda(billetera.getMoneda() != null ? billetera.getMoneda() : "ARS");
        movimiento.setSaldoResultante(nuevoSaldo);
        movimiento.setEstado(EstadoMovimiento.CONFIRMADO);
        movimiento.setReferenciaExterna(referencia);
        movimiento.setDescripcion(descripcion != null && !descripcion.isBlank() ? descripcion : "Carga de saldo");

        try {
            movimiento = movimientoRepository.saveAndFlush(movimiento);
        } catch (DataIntegrityViolationException e) {
            if (esViolacionReferenciaUnica(e)) {
                throw new ConflictoException(CodigoError.CLAVE_IDEMPOTENCIA_USADA,
                        "La referencia ya fue utilizada en otra operación");
            }
            throw e;
        }

        // 7. Registro de auditoría
        Map<String, Object> detalleAuditoria = new LinkedHashMap<>();
        detalleAuditoria.put("monto", montoAcreditado);
        detalleAuditoria.put("referencia", referencia);
        detalleAuditoria.put("descripcion", movimiento.getDescripcion());
        detalleAuditoria.put("saldoResultante", nuevoSaldo);
        detalleAuditoria.put("movimientoId", movimiento.getId());
        auditoriaService.registrar(usuarioId, AccionAuditoria.PAGO, "BILLETERA", billetera.getId().toString(), detalleAuditoria);

        log.info("Carga acreditada con éxito para usuario {}: {} {}, nuevo saldo: {}, referencia: {}",
                usuarioId, montoAcreditado, movimiento.getMoneda(), nuevoSaldo, referencia);

        return movimiento;
    }

    /**
     * Registra un intento de carga rechazado por la pasarela de pagos.
     * Los saldos de la billetera se mantienen intactos y queda registrado el evento en auditoría
     * dentro de una transacción autónoma independiente (REQUIRES_NEW) para sobrevivir a rollbacks externos.
     */
    @Transactional
    public Auditoria registrarRechazo(Long usuarioId, BigDecimal monto, String referencia, String motivo) {
        log.warn("Registrando carga rechazada para usuario {}: monto={}, referencia={}, motivo={}",
                usuarioId, monto, referencia, motivo);

        Billetera billetera = billeteraRepository.findByUsuarioId(usuarioId).orElse(null);
        String entidadId = billetera != null ? billetera.getId().toString() : String.valueOf(usuarioId);

        Map<String, Object> detalle = new LinkedHashMap<>();
        if (monto != null) {
            detalle.put("monto", monto.setScale(2, RoundingMode.HALF_UP));
        }
        if (referencia != null) {
            detalle.put("referencia", referencia);
        }
        if (motivo != null) {
            detalle.put("motivo", motivo);
        }
        detalle.put("estado", "RECHAZADA");

        return auditoriaService.registrarEnTransaccionNueva(usuarioId, AccionAuditoria.PAGO, "BILLETERA", entidadId, detalle);
    }

    private Movimiento validarYDevolverExistente(Movimiento existente, Long usuarioId) {
        if (!existente.getBilletera().getUsuario().getId().equals(usuarioId)
                || existente.getTipo() != TipoMovimiento.CARGA
                || existente.getEstado() != EstadoMovimiento.CONFIRMADO) {
            throw new ConflictoException(CodigoError.CLAVE_IDEMPOTENCIA_USADA,
                    "La referencia ya fue utilizada en otra operación");
        }
        return existente;
    }

    private static boolean esViolacionReferenciaUnica(DataIntegrityViolationException e) {
        Throwable causa = e;
        while (causa != null && !(causa instanceof ConstraintViolationException)) {
            causa = causa.getCause();
        }
        if (causa instanceof ConstraintViolationException cve) {
            String nombre = cve.getConstraintName();
            if (nombre != null && nombre.equalsIgnoreCase("uq_movimiento_referencia_externa")) {
                return true;
            }
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("uq_movimiento_referencia_externa");
    }
}
