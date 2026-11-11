package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La liquidación de un torneo finalizado (HU-606): reparte lo que quedó en su custodia y la libera, todo en una
 * transacción.
 * <ul>
 * <li>Los gastos administrativos que pagó cada inscripto van a la billetera de la plataforma (la que no tiene usuario,
 * V40).</li>
 * <li>El organizador recibe los gastos del torneo, hasta lo que se recaudó.</li>
 * <li>Lo que queda es el pozo y va a los ganadores con los porcentajes sellados al publicar ({@link RepartoPorPuesto});
 * en equipos cobra el capitán. Si nadie terminó en un puesto con premio, va al organizador.</li>
 * </ul>
 * Antes de pagar se controla que lo repartido sume exactamente lo retenido: si no da, no se paga nada y queda una
 * alerta en la auditoría. Cada acreditación es un movimiento con una referencia única y queda en la auditoría como
 * liquidación del torneo. La custodia LIBERADA es la marca de liquidado: un reintento da 409 CUSTODIA_LIBERADA.
 */
@Service
public class LiquidacionService {

    static final String SIN_CERRAR = "El evento debe estar cerrado y validado oficialmente para liberar la custodia";

    private static final String ENTIDAD = "TORNEO";

    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final PremioRepository premioRepository;
    private final GastoRepository gastoRepository;
    private final CustodiaRepository custodiaRepository;
    private final CustodiaService custodiaService;
    private final MovimientoRepository movimientoRepository;
    private final BilleteraRepository billeteraRepository;
    private final UsuarioRepository usuarioRepository;
    private final AuditoriaService auditoriaService;
    private final TransactionTemplate aparte;

    public LiquidacionService(TorneoRepository torneoRepository, InscripcionRepository inscripcionRepository,
                              PremioRepository premioRepository, GastoRepository gastoRepository,
                              CustodiaRepository custodiaRepository, CustodiaService custodiaService,
                              MovimientoRepository movimientoRepository, BilleteraRepository billeteraRepository,
                              UsuarioRepository usuarioRepository, AuditoriaService auditoriaService,
                              PlatformTransactionManager transacciones) {
        this.torneoRepository = torneoRepository;
        this.inscripcionRepository = inscripcionRepository;
        this.premioRepository = premioRepository;
        this.gastoRepository = gastoRepository;
        this.custodiaRepository = custodiaRepository;
        this.custodiaService = custodiaService;
        this.movimientoRepository = movimientoRepository;
        this.billeteraRepository = billeteraRepository;
        this.usuarioRepository = usuarioRepository;
        this.auditoriaService = auditoriaService;
        // la alerta queda aunque la liquidación se deshaga
        this.aparte = new TransactionTemplate(transacciones);
        this.aparte.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Lo que se le acredita a una billetera. */
    public enum Rol { PREMIO, ORGANIZADOR, PLATAFORMA }

    /** {@code usuarioId} es null en la de la plataforma. */
    public record Acreditacion(Rol rol, Long usuarioId, BigDecimal monto, Long inscripcionId, Integer puesto) {
    }

    public record LiquidacionTorneo(Long torneoId, Long custodiaId, BigDecimal retenido, List<Acreditacion> acreditaciones) {
    }

    /**
     * Liquida un torneo FINALIZADO. Sin terminar (o cancelado, que se reintegra): 409 FONDOS_EN_CUSTODIA. Ya
     * liquidado: 409 CUSTODIA_LIBERADA.
     */
    @Transactional
    public LiquidacionTorneo liquidarTorneo(Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndEliminadoEnIsNull(torneoId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + torneoId));
        if (torneo.getEstado() != EstadoTorneo.FINALIZADO) {
            throw new FondosEnCustodiaException(SIN_CERRAR);
        }
        // un torneo sin pagos también queda liquidado, con su custodia vacía
        custodiaRepository.crearDelTorneo(torneoId);
        Custodia custodia = custodiaRepository.findParaLiberarPorTorneoId(torneoId).orElseThrow();
        if (custodia.getEstado() != EstadoCustodia.RETENIDA) {
            throw new ConflictoException(CodigoError.CUSTODIA_LIBERADA,
                    "El torneo " + torneoId + " ya se liquidó").con("estado", custodia.getEstado());
        }

        Retenido retenido = retenido(custodia.getId());
        List<Acreditacion> acreditaciones = repartir(torneo, retenido);
        BigDecimal repartido = acreditaciones.stream().map(Acreditacion::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (repartido.compareTo(retenido.total()) != 0) {
            alertar(torneoId, custodia.getId(), retenido.total(), repartido);
            throw new IllegalStateException("La liquidación del torneo " + torneoId + " reparte " + repartido
                    + " y la custodia retiene " + retenido.total() + ": no se pagó nada");
        }

        String nombre = torneo.getNombre();
        Long custodiaId = custodia.getId();
        custodiaService.liberarTorneo(torneoId); // deja la sesión vacía: de acá en adelante, por id
        acreditaciones.stream()
                // siempre en el mismo orden, para no trabarse con otra transacción que bloquee billeteras
                .sorted(Comparator.comparing(Acreditacion::usuarioId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(a -> acreditar(torneoId, nombre, a));
        return new LiquidacionTorneo(torneoId, custodiaId, retenido.total(), acreditaciones);
    }

    // --- el reparto ---------------------------------------------------------------------------------

    private List<Acreditacion> repartir(Torneo torneo, Retenido retenido) {
        List<Acreditacion> acreditaciones = new ArrayList<>();
        Long organizadorId = torneo.getOrganizador().getId();

        BigDecimal gastos = gastoRepository.totalDelTorneo(torneo.getId());
        BigDecimal alOrganizador = gastos.min(retenido.recaudado());
        BigDecimal pozo = retenido.recaudado().subtract(alOrganizador);

        Map<Long, Inscripcion> conPuesto = inscripcionRepository.conPuestoDelTorneo(torneo.getId()).stream()
                .collect(Collectors.toMap(Inscripcion::getId, i -> i, (a, b) -> a, LinkedHashMap::new));
        Map<Integer, BigDecimal> pctPorPuesto = premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId())
                .stream().collect(Collectors.toMap(p -> p.getId().getPuesto(), Premio::getPct));
        List<RepartoPorPuesto.Cobro> cobros = RepartoPorPuesto.repartir(pozo, pctPorPuesto, conPuesto.values().stream()
                .map(i -> new RepartoPorPuesto.Posicionado(i.getId(), i.getPosicion())).toList());
        for (RepartoPorPuesto.Cobro cobro : cobros) {
            Inscripcion inscripcion = conPuesto.get(cobro.inscripcionId());
            acreditaciones.add(new Acreditacion(Rol.PREMIO, inscripcion.titular().getId(), cobro.monto(),
                    inscripcion.getId(), cobro.puesto()));
        }
        if (cobros.isEmpty()) {
            alOrganizador = alOrganizador.add(pozo);
        }

        agregar(acreditaciones, Rol.ORGANIZADOR, organizadorId, alOrganizador);
        agregar(acreditaciones, Rol.PLATAFORMA, null, retenido.gastosAdministrativos());
        return acreditaciones;
    }

    private static void agregar(List<Acreditacion> acreditaciones, Rol rol, Long usuarioId, BigDecimal monto) {
        if (monto.signum() > 0) {
            acreditaciones.add(new Acreditacion(rol, usuarioId, monto, null, null));
        }
    }

    /** Lo que entró a la custodia: las inscripciones, los gastos administrativos y el total, con cualquier otro. */
    private Retenido retenido(Long custodiaId) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal recaudado = BigDecimal.ZERO;
        BigDecimal gastos = BigDecimal.ZERO;
        for (Object[] fila : movimientoRepository.totalesDeLaCustodia(custodiaId)) {
            TipoMovimiento tipo = (TipoMovimiento) fila[0];
            BigDecimal suma = (BigDecimal) fila[1];
            total = total.add(suma);
            if (tipo == TipoMovimiento.INSCRIPCION) {
                recaudado = recaudado.add(suma);
            } else if (tipo == TipoMovimiento.COMISION) {
                gastos = gastos.add(suma);
            }
        }
        return new Retenido(total, recaudado, gastos);
    }

    private record Retenido(BigDecimal total, BigDecimal recaudado, BigDecimal gastosAdministrativos) {
    }

    // --- las acreditaciones -------------------------------------------------------------------------

    private void acreditar(Long torneoId, String torneo, Acreditacion acreditacion) {
        Billetera billetera = acreditacion.rol() == Rol.PLATAFORMA
                ? billeteraRepository.findPlataformaConBloqueo()
                        .orElseThrow(() -> new IllegalStateException("Falta la billetera de la plataforma (V40)"))
                : bloqueada(acreditacion.usuarioId());
        billetera.setSaldoDisponible(billetera.getSaldoDisponible().add(acreditacion.monto()));
        billetera.setGananciaAcumulada(billetera.getGananciaAcumulada().add(acreditacion.monto()));
        billeteraRepository.save(billetera);

        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        movimiento.setTorneo(torneoRepository.getReferenceById(torneoId));
        movimiento.setTipo(acreditacion.rol() == Rol.PREMIO ? TipoMovimiento.PREMIO : TipoMovimiento.LIQUIDACION);
        movimiento.setMonto(acreditacion.monto());
        movimiento.setMoneda(billetera.getMoneda());
        movimiento.setSaldoResultante(billetera.getSaldoDisponible());
        movimiento.setEstado(EstadoMovimiento.CONFIRMADO);
        movimiento.setReferenciaExterna(referencia(torneoId, acreditacion));
        movimiento.setDescripcion(descripcion(torneo, acreditacion));
        if (acreditacion.inscripcionId() != null) {
            movimiento.setInscripcion(inscripcionRepository.getReferenceById(acreditacion.inscripcionId()));
        }
        Movimiento guardado = movimientoRepository.save(movimiento);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", acreditacion.rol() == Rol.PREMIO ? "LIQUIDACION_PREMIO" : "LIQUIDACION_TORNEO");
        detalle.put("rol", acreditacion.rol());
        detalle.put("movimientoId", guardado.getId());
        detalle.put("usuarioId", acreditacion.usuarioId());
        detalle.put("monto", acreditacion.monto());
        if (acreditacion.puesto() != null) {
            detalle.put("puesto", acreditacion.puesto());
            detalle.put("inscripcionId", acreditacion.inscripcionId());
        }
        auditoriaService.registrar(null, AccionAuditoria.LIQUIDACION, ENTIDAD, torneoId.toString(), detalle);
    }

    /** Una por acreditación: la base no deja repetirla (V38), así nada se paga dos veces. */
    static String referencia(Long torneoId, Acreditacion acreditacion) {
        return switch (acreditacion.rol()) {
            case PREMIO -> "liq-t" + torneoId + "-premio-" + acreditacion.inscripcionId();
            case ORGANIZADOR -> "liq-t" + torneoId + "-organizador";
            case PLATAFORMA -> "liq-t" + torneoId + "-plataforma";
        };
    }

    private static String descripcion(String torneo, Acreditacion acreditacion) {
        String texto = switch (acreditacion.rol()) {
            case PREMIO -> "Premio del " + acreditacion.puesto() + ".º puesto en " + torneo;
            case ORGANIZADOR -> "Liquidación del torneo " + torneo + ": gastos del torneo";
            case PLATAFORMA -> "Liquidación del torneo " + torneo + ": gastos administrativos";
        };
        return texto.length() > 255 ? texto.substring(0, 255) : texto;
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

    private void alertar(Long torneoId, Long custodiaId, BigDecimal retenido, BigDecimal repartido) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("alerta", "REPARTO_NO_CUADRA");
        detalle.put("custodiaId", custodiaId);
        detalle.put("retenido", retenido);
        detalle.put("repartido", repartido);
        aparte.executeWithoutResult(estado -> auditoriaService.registrar(null, AccionAuditoria.LIQUIDACION, ENTIDAD,
                torneoId.toString(), detalle));
    }
}
