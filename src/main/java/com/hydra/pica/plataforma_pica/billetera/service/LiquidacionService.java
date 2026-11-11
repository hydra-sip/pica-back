package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditador.Evento;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * La liquidación de un torneo finalizado (HU-606): reparte lo que quedó en su custodia y la libera, todo en una
 * transacción.
 * <ul>
 * <li>Los gastos administrativos que pagó cada inscripto van a la billetera de la plataforma (la que no tiene usuario,
 * V40).</li>
 * <li>El organizador recibe los gastos del torneo, hasta lo que se recaudó.</li>
 * <li>Lo que queda es el pozo y va a los ganadores con los porcentajes sellados al publicar ({@link RepartoPorPuesto});
 * en equipos cobra el capitán. Si nadie terminó en un puesto con premio, va al organizador.</li>
 * <li>Lo que aporta su rifa al pozo se reparte igual, cuando terminaron los dos: si la rifa se liquidó antes, acá; si
 * no, al liquidarse la rifa ({@link #pagarAporteDeLaRifa}).</li>
 * </ul>
 * Antes de pagar se controla que lo repartido sume exactamente lo retenido: si no da, no se paga nada y queda una
 * alerta en la auditoría. La custodia LIBERADA es la marca de liquidado: un reintento da 409 CUSTODIA_LIBERADA.
 */
@Service
public class LiquidacionService {

    static final String SIN_CERRAR = "El evento debe estar cerrado y validado oficialmente para liberar la custodia";

    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final PremioRepository premioRepository;
    private final GastoRepository gastoRepository;
    private final RifaRepository rifaRepository;
    private final CustodiaRepository custodiaRepository;
    private final CustodiaService custodiaService;
    private final MovimientoRepository movimientoRepository;
    private final Acreditador acreditador;

    public LiquidacionService(TorneoRepository torneoRepository, InscripcionRepository inscripcionRepository,
                              PremioRepository premioRepository, GastoRepository gastoRepository,
                              RifaRepository rifaRepository, CustodiaRepository custodiaRepository,
                              CustodiaService custodiaService, MovimientoRepository movimientoRepository,
                              Acreditador acreditador) {
        this.torneoRepository = torneoRepository;
        this.inscripcionRepository = inscripcionRepository;
        this.premioRepository = premioRepository;
        this.gastoRepository = gastoRepository;
        this.rifaRepository = rifaRepository;
        this.custodiaRepository = custodiaRepository;
        this.custodiaService = custodiaService;
        this.movimientoRepository = movimientoRepository;
        this.acreditador = acreditador;
    }

    public record LiquidacionTorneo(Long torneoId, Long custodiaId, BigDecimal retenido,
                                    List<Acreditacion> acreditaciones) {
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

        Retenido retenido = retenido(movimientoRepository, custodia.getId());
        String nombre = torneo.getNombre();
        String ref = "liq-t" + torneoId;
        BigDecimal gastos = gastoRepository.totalDelTorneo(torneoId);
        BigDecimal alOrganizador = gastos.min(retenido.recaudado());

        List<Acreditacion> acreditaciones = new ArrayList<>(entreLosGanadores(torneo,
                retenido.recaudado().subtract(alOrganizador), ref,
                puesto -> "Premio del " + puesto + ".º puesto en " + nombre,
                "Liquidación del torneo " + nombre + ": gastos del torneo"));
        if (alOrganizador.signum() > 0) {
            acreditaciones.add(new Acreditacion(Rol.ORGANIZADOR, torneo.getOrganizador().getId(), alOrganizador, null,
                    null, ref + "-organizador", "Liquidación del torneo " + nombre + ": gastos del torneo"));
        }
        if (retenido.gastosAdministrativos().signum() > 0) {
            acreditaciones.add(new Acreditacion(Rol.PLATAFORMA, null, retenido.gastosAdministrativos(), null, null,
                    ref + "-plataforma", "Liquidación del torneo " + nombre + ": gastos administrativos"));
        }
        BigDecimal repartido = Acreditador.suma(acreditaciones);
        if (repartido.compareTo(retenido.total()) != 0) {
            acreditador.alertar(Evento.torneo(torneoId), custodia.getId(), retenido.total(), repartido);
            throw new IllegalStateException("La liquidación del torneo " + torneoId + " reparte " + repartido
                    + " y la custodia retiene " + retenido.total() + ": no se pagó nada");
        }

        Long custodiaId = custodia.getId();
        custodiaService.liberarTorneo(torneoId); // deja la sesión vacía: de acá en adelante, por id
        acreditador.acreditar(Evento.torneo(torneoId), juntarLosDelOrganizador(acreditaciones));

        // la rifa ya se liquidó y su aporte al pozo esperaba a que terminara el torneo
        rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneoId)
                .filter(rifa -> rifa.getEstado() == EstadoRifa.LIQUIDADA)
                .ifPresent(rifa -> pagarAporteDeLaRifa(rifa.getId()));
        return new LiquidacionTorneo(torneoId, custodiaId, retenido.total(), acreditaciones);
    }

    /**
     * Lo que una rifa liquidada aporta al pozo de su torneo ya liquidado: se reparte entre los ganadores del torneo
     * como su pozo y libera la custodia de la rifa. Si ya se pagó (la custodia está liberada), no hace nada.
     *
     * @return si lo pagó ahora
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean pagarAporteDeLaRifa(Long rifaId) {
        Optional<Custodia> encontrada = custodiaRepository.findParaLiberarPorRifaId(rifaId);
        if (encontrada.isEmpty() || encontrada.get().getEstado() != EstadoCustodia.RETENIDA) {
            return false;
        }
        Rifa rifa = rifaRepository.findByIdAndEliminadoEnIsNull(rifaId).orElseThrow();
        Torneo torneo = rifa.getTorneo();
        BigDecimal aporte = RepartoRifa.deLoRecaudado(retenido(movimientoRepository, encontrada.get().getId()).vendido(),
                rifa.getPctDestinadoTorneo()).alPozoTorneo();
        String rifaTitulo = rifa.getTitulo();
        String torneoNombre = torneo.getNombre();
        List<Acreditacion> acreditaciones = entreLosGanadores(torneo, aporte, "liq-r" + rifaId + "-aporte",
                puesto -> "Premio del " + puesto + ".º puesto en " + torneoNombre + ": lo que aporta la rifa "
                        + rifaTitulo,
                "Liquidación del torneo " + torneoNombre + ": lo que aporta la rifa " + rifaTitulo);
        Long torneoId = torneo.getId();
        custodiaService.liberarRifa(rifaId); // deja la sesión vacía
        acreditador.acreditar(Evento.aporte(torneoId, rifaId), acreditaciones);
        return true;
    }

    /**
     * Un pozo entre los que terminaron en un puesto con premio, en equipos al capitán. Sin ninguno, va al organizador
     * con la referencia {@code ref + "-organizador"}.
     */
    private List<Acreditacion> entreLosGanadores(Torneo torneo, BigDecimal pozo, String ref,
                                                 IntFunction<String> descripcionDelPremio,
                                                 String descripcionDelOrganizador) {
        if (pozo.signum() <= 0) {
            return List.of();
        }
        Map<Long, Inscripcion> conPuesto = inscripcionRepository.conPuestoDelTorneo(torneo.getId()).stream()
                .collect(Collectors.toMap(Inscripcion::getId, i -> i, (a, b) -> a, LinkedHashMap::new));
        Map<Integer, BigDecimal> pctPorPuesto = premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId())
                .stream().collect(Collectors.toMap(p -> p.getId().getPuesto(), Premio::getPct));
        List<RepartoPorPuesto.Cobro> cobros = RepartoPorPuesto.repartir(pozo, pctPorPuesto, conPuesto.values().stream()
                .map(i -> new RepartoPorPuesto.Posicionado(i.getId(), i.getPosicion())).toList());
        if (cobros.isEmpty()) {
            return List.of(new Acreditacion(Rol.ORGANIZADOR, torneo.getOrganizador().getId(), pozo, null, null,
                    ref + "-organizador", descripcionDelOrganizador));
        }
        List<Acreditacion> acreditaciones = new ArrayList<>();
        for (RepartoPorPuesto.Cobro cobro : cobros) {
            Inscripcion inscripcion = conPuesto.get(cobro.inscripcionId());
            acreditaciones.add(new Acreditacion(Rol.PREMIO, inscripcion.titular().getId(), cobro.monto(),
                    inscripcion.getId(), cobro.puesto(), ref + "-premio-" + inscripcion.getId(),
                    descripcionDelPremio.apply(cobro.puesto())));
        }
        return acreditaciones;
    }

    /** Si el pozo fue al organizador porque nadie cobra, va en un solo movimiento con sus gastos. */
    private static List<Acreditacion> juntarLosDelOrganizador(List<Acreditacion> acreditaciones) {
        List<Acreditacion> delOrganizador = acreditaciones.stream().filter(a -> a.rol() == Rol.ORGANIZADOR).toList();
        if (delOrganizador.size() < 2) {
            return acreditaciones;
        }
        Acreditacion primera = delOrganizador.get(0);
        List<Acreditacion> juntas = new ArrayList<>(acreditaciones.stream().filter(a -> a.rol() != Rol.ORGANIZADOR)
                .toList());
        juntas.add(new Acreditacion(Rol.ORGANIZADOR, primera.usuarioId(), Acreditador.suma(delOrganizador), null, null,
                primera.referencia(), primera.descripcion()));
        return juntas;
    }

    /**
     * Lo que entró a una custodia: las inscripciones, los números vendidos, los gastos administrativos y el total, con
     * cualquier otro movimiento que la liquidación no sepa repartir.
     */
    static Retenido retenido(MovimientoRepository movimientoRepository, Long custodiaId) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal recaudado = BigDecimal.ZERO;
        BigDecimal vendido = BigDecimal.ZERO;
        BigDecimal gastos = BigDecimal.ZERO;
        for (Object[] fila : movimientoRepository.totalesDeLaCustodia(custodiaId)) {
            TipoMovimiento tipo = (TipoMovimiento) fila[0];
            BigDecimal suma = (BigDecimal) fila[1];
            total = total.add(suma);
            switch (tipo) {
                case INSCRIPCION -> recaudado = recaudado.add(suma);
                case COMPRA_NUMERO -> vendido = vendido.add(suma);
                case COMISION -> gastos = gastos.add(suma);
                default -> {
                    // queda en el total y no en las partes: la liquidación no cuadra y no paga
                }
            }
        }
        return new Retenido(total, recaudado, vendido, gastos);
    }

    record Retenido(BigDecimal total, BigDecimal recaudado, BigDecimal vendido, BigDecimal gastosAdministrativos) {
    }
}
