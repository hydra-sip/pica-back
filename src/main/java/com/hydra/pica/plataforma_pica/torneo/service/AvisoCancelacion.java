package com.hydra.pica.plataforma_pica.torneo.service;

import static com.hydra.pica.plataforma_pica.billetera.service.Reintegrador.pesos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.GastoCancelacion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
import com.hydra.pica.plataforma_pica.torneo.repository.CancelacionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoCancelacionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Los mails de una cancelación resuelta (HU-605): a cada uno que pagó (el titular, o el capitán en equipos), con lo que
 * le devolvieron, el porcentaje sobre lo que pagó y, si soporte aprobó gastos, el desglose; al organizador, la decisión
 * de soporte. Van después del commit: si un mail falla, la cancelación queda igual y los demás salen.
 */
@Component
public class AvisoCancelacion {

    private static final Logger log = LoggerFactory.getLogger(AvisoCancelacion.class);
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final CancelacionRepository cancelacionRepository;
    private final GastoCancelacionRepository gastoCancelacionRepository;
    private final CustodiaRepository custodiaRepository;
    private final MovimientoRepository movimientoRepository;
    private final EmailService emailService;

    public AvisoCancelacion(CancelacionRepository cancelacionRepository,
                            GastoCancelacionRepository gastoCancelacionRepository,
                            CustodiaRepository custodiaRepository, MovimientoRepository movimientoRepository,
                            EmailService emailService) {
        this.cancelacionRepository = cancelacionRepository;
        this.gastoCancelacionRepository = gastoCancelacionRepository;
        this.custodiaRepository = custodiaRepository;
        this.movimientoRepository = movimientoRepository;
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alResolver(CancelacionResuelta evento) {
        avisar(evento);
    }

    /** Sin transacción propia: los tests lo llaman dentro de la suya, que no se confirma. */
    void avisar(CancelacionResuelta evento) {
        Cancelacion cancelacion = cancelacionRepository.findByTorneoId(evento.torneoId()).orElse(null);
        if (cancelacion == null || cancelacion.getEstado() == EstadoCancelacion.EN_REVISION) {
            return;
        }
        Torneo torneo = cancelacion.getTorneo();
        List<GastoCancelacion> gastos = cancelacion.getEstado() == EstadoCancelacion.APROBADA
                ? gastoCancelacionRepository.findByCancelacionIdOrderByIdAsc(cancelacion.getId()) : List.of();
        Map<Long, BigDecimal> pagado = pagadoPorBilletera(torneo.getId());
        for (Movimiento reintegro : movimientoRepository.reintegrosDelTorneo(torneo.getId())) {
            BigDecimal pago = pagado.getOrDefault(reintegro.getBilletera().getId(), reintegro.getMonto());
            enviar(reintegro.getBilletera().getUsuario().getEmail(), "Se canceló " + torneo.getNombre(),
                    alQuePago(torneo, cancelacion, gastos, pago, reintegro.getMonto()), torneo.getId());
        }
        if (cancelacion.getEstado() != EstadoCancelacion.AUTOMATICA) {
            enviar(torneo.getOrganizador().getEmail(), "Soporte revisó la cancelación de " + torneo.getNombre(),
                    alOrganizador(torneo, cancelacion, gastos), torneo.getId());
        }
    }

    private String alQuePago(Torneo torneo, Cancelacion cancelacion, List<GastoCancelacion> gastos, BigDecimal pagado,
                             BigDecimal devuelto) {
        String encabezado = "Se canceló %s. Motivo: %s\n\n".formatted(torneo.getNombre(), torneo.getMotivoCancelacion());
        return switch (cancelacion.getEstado()) {
            case AUTOMATICA -> encabezado + "Te devolvimos %s (100 %% de lo que pagaste) a tu billetera.\n"
                    .formatted(pesos(devuelto));
            case RECHAZADA -> encabezado + "Soporte rechazó los gastos que declaró el organizador: %s\n\n"
                    .formatted(oracion(cancelacion.getMotivoRechazo()))
                    + "Te devolvimos %s (100 %%) a tu billetera.\n".formatted(pesos(devuelto));
            case APROBADA -> encabezado + "Soporte aprobó gastos por %s: %s. Te tocan %s de esos gastos.\n\n"
                    .formatted(pesos(total(gastos)), desglose(gastos), pesos(pagado.subtract(devuelto)))
                    + "Te devolvimos %s de los %s que pagaste (%s %%) a tu billetera.\n"
                    .formatted(pesos(devuelto), pesos(pagado), porcentaje(devuelto, pagado));
            case EN_REVISION -> throw new IllegalStateException("Una cancelación en revisión no se avisa");
        };
    }

    private static String alOrganizador(Torneo torneo, Cancelacion cancelacion, List<GastoCancelacion> gastos) {
        if (cancelacion.getEstado() == EstadoCancelacion.APROBADA) {
            return "Soporte aprobó los gastos de la cancelación de %s: te acreditamos %s.\n"
                    .formatted(torneo.getNombre(), pesos(total(gastos)));
        }
        return "Soporte rechazó los gastos de la cancelación de %s: %s Se devolvió todo a los participantes.\n"
                .formatted(torneo.getNombre(), oracion(cancelacion.getMotivoRechazo()));
    }

    /** Lo que pagó con saldo cada billetera en la custodia del torneo: inscripción y gastos administrativos. */
    private Map<Long, BigDecimal> pagadoPorBilletera(Long torneoId) {
        Map<Long, BigDecimal> pagado = new HashMap<>();
        custodiaRepository.findByTorneoId(torneoId).ifPresent(custodia -> {
            for (Object[] fila : movimientoRepository.pagosConSaldoPorBilletera(custodia.getId())) {
                pagado.merge((Long) fila[0], (BigDecimal) fila[2], BigDecimal::add);
            }
        });
        return pagado;
    }

    private static BigDecimal total(List<GastoCancelacion> gastos) {
        return gastos.stream().map(GastoCancelacion::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String desglose(List<GastoCancelacion> gastos) {
        return gastos.stream().map(g -> g.getConcepto() + " " + pesos(g.getMonto())).collect(Collectors.joining(", "));
    }

    /** 86,35: lo devuelto sobre lo pagado, con hasta dos decimales. */
    private static String porcentaje(BigDecimal devuelto, BigDecimal pagado) {
        return devuelto.multiply(CIEN).divide(pagado, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                .replace('.', ',');
    }

    /** El motivo con su punto final, sin repetirlo si ya lo trae. */
    private static String oracion(String texto) {
        String limpio = texto.strip();
        return limpio.matches(".*[.!?]$") ? limpio : limpio + ".";
    }

    private void enviar(String email, String asunto, String texto, Long torneoId) {
        try {
            emailService.enviarAviso(email, asunto, texto);
        } catch (RuntimeException e) {
            log.warn("No se pudo mandar el aviso de la cancelación del torneo {} a {}", torneoId, email, e);
        }
    }
}
