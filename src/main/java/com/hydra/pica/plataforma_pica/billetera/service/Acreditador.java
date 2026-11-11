package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Hace las acreditaciones de la liquidación (HU-606), del torneo y de la rifa: suma al disponible y a las ganancias,
 * deja el movimiento confirmado con el evento y lo anota en la auditoría como liquidación.
 */
@Component
public class Acreditador {

    private final BilleteraRepository billeteraRepository;
    private final MovimientoRepository movimientoRepository;
    private final TorneoRepository torneoRepository;
    private final RifaRepository rifaRepository;
    private final InscripcionRepository inscripcionRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;
    private final TransactionTemplate aparte;

    public Acreditador(BilleteraRepository billeteraRepository, MovimientoRepository movimientoRepository,
                       TorneoRepository torneoRepository, RifaRepository rifaRepository,
                       InscripcionRepository inscripcionRepository, UsuarioRepository usuarioRepository,
                       AuditoriaService auditoriaService, PlatformTransactionManager transacciones) {
        this.billeteraRepository = billeteraRepository;
        this.movimientoRepository = movimientoRepository;
        this.torneoRepository = torneoRepository;
        this.rifaRepository = rifaRepository;
        this.inscripcionRepository = inscripcionRepository;
        this.usuarioRepository = usuarioRepository;
        this.auditoriaService = auditoriaService;
        // la alerta queda aunque la liquidación se deshaga
        this.aparte = new TransactionTemplate(transacciones);
        this.aparte.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Qué se liquida: el torneo o la rifa del movimiento, y dónde queda en la auditoría. */
    public record Evento(Long torneoId, Long rifaId, String entidad, Long entidadId) {

        static Evento torneo(Long torneoId) {
            return new Evento(torneoId, null, "TORNEO", torneoId);
        }

        static Evento rifa(Long rifaId) {
            return new Evento(null, rifaId, "RIFA", rifaId);
        }

        /** Lo que una rifa aporta al pozo de su torneo: movimiento de los dos, auditoría de la rifa. */
        static Evento aporte(Long torneoId, Long rifaId) {
            return new Evento(torneoId, rifaId, "RIFA", rifaId);
        }
    }

    /** Siempre en el mismo orden, para no trabarse con otra transacción que bloquee billeteras. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acreditar(Evento evento, List<Acreditacion> acreditaciones) {
        acreditaciones.stream()
                .sorted(Comparator.comparing(Acreditacion::usuarioId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(a -> acreditar(evento, a));
    }

    private void acreditar(Evento evento, Acreditacion acreditacion) {
        Billetera billetera = acreditacion.rol() == Rol.PLATAFORMA
                ? billeteraRepository.findPlataformaConBloqueo()
                        .orElseThrow(() -> new IllegalStateException("Falta la billetera de la plataforma (V40)"))
                : bloqueada(acreditacion.usuarioId());
        billetera.setSaldoDisponible(billetera.getSaldoDisponible().add(acreditacion.monto()));
        billetera.setGananciaAcumulada(billetera.getGananciaAcumulada().add(acreditacion.monto()));
        billeteraRepository.save(billetera);

        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        if (evento.torneoId() != null) {
            movimiento.setTorneo(torneoRepository.getReferenceById(evento.torneoId()));
        }
        if (evento.rifaId() != null) {
            movimiento.setRifa(rifaRepository.getReferenceById(evento.rifaId()));
        }
        if (acreditacion.inscripcionId() != null) {
            movimiento.setInscripcion(inscripcionRepository.getReferenceById(acreditacion.inscripcionId()));
        }
        movimiento.setTipo(switch (acreditacion.rol()) {
            case PREMIO -> TipoMovimiento.PREMIO;
            case HONORARIO -> TipoMovimiento.HONORARIO;
            default -> TipoMovimiento.LIQUIDACION;
        });
        movimiento.setMonto(acreditacion.monto());
        movimiento.setMoneda(billetera.getMoneda());
        movimiento.setSaldoResultante(billetera.getSaldoDisponible());
        movimiento.setEstado(EstadoMovimiento.CONFIRMADO);
        movimiento.setReferenciaExterna(acreditacion.referencia());
        String descripcion = acreditacion.descripcion();
        movimiento.setDescripcion(descripcion.length() > 255 ? descripcion.substring(0, 255) : descripcion);
        Movimiento guardado = movimientoRepository.save(movimiento);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", switch (acreditacion.rol()) {
            case PREMIO -> "LIQUIDACION_PREMIO";
            case HONORARIO -> "LIQUIDACION_HONORARIO";
            default -> "LIQUIDACION_" + evento.entidad();
        });
        detalle.put("rol", acreditacion.rol());
        detalle.put("movimientoId", guardado.getId());
        detalle.put("usuarioId", acreditacion.usuarioId());
        detalle.put("monto", acreditacion.monto());
        if (acreditacion.puesto() != null) {
            detalle.put("puesto", acreditacion.puesto());
        }
        if (acreditacion.inscripcionId() != null) {
            detalle.put("inscripcionId", acreditacion.inscripcionId());
        }
        auditoriaService.registrar(null, AccionAuditoria.LIQUIDACION, evento.entidad(), evento.entidadId().toString(),
                detalle);
    }

    /** Lo repartido no da lo retenido: queda la alerta en la auditoría, en su propia transacción. */
    public void alertar(Evento evento, Long custodiaId, BigDecimal retenido, BigDecimal repartido) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("alerta", "REPARTO_NO_CUADRA");
        detalle.put("custodiaId", custodiaId);
        detalle.put("retenido", retenido);
        detalle.put("repartido", repartido);
        aparte.executeWithoutResult(estado -> auditoriaService.registrar(null, AccionAuditoria.LIQUIDACION,
                evento.entidad(), evento.entidadId().toString(), detalle));
    }

    static BigDecimal suma(List<Acreditacion> acreditaciones) {
        return acreditaciones.stream().map(Acreditacion::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Billetera bloqueada(Long usuarioId) {
        return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElseGet(() -> {
            // respaldo por si algún test persiste un Usuario directo sin pasar por UsuarioService.crear
            Billetera nueva = new Billetera();
            nueva.setUsuario(usuarioRepository.getReferenceById(usuarioId));
            billeteraRepository.saveAndFlush(nueva);
            return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElseThrow();
        });
    }
}
