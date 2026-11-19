package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
import com.hydra.pica.plataforma_pica.equipo.domain.Nomina;
import com.hydra.pica.plataforma_pica.equipo.repository.NominaRepository;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.RepartoPremio;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
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
 * <li>El organizador recibe los gastos del torneo, hasta lo que se recaudó. Un honorario arbitral cuenta solo si lo
 * depositó en la custodia (HU-604): si no, queda en el pozo.</li>
 * <li>Lo que depositó para cada árbitro se le acredita al árbitro.</li>
 * <li>Lo que queda es el pozo y va a los ganadores con los porcentajes sellados al publicar ({@link RepartoPorPuesto});
 * en equipos se divide entre los integrantes según el reparto de su nómina, con los centavos que sobran al capitán
 * (CR-05); una inscripción sin reparto cobra todo el capitán. Si nadie terminó en un puesto con premio, va al organizador.</li>
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
    private final NominaRepository nominaRepository;

    public LiquidacionService(TorneoRepository torneoRepository, InscripcionRepository inscripcionRepository,
                              PremioRepository premioRepository, GastoRepository gastoRepository,
                              RifaRepository rifaRepository, CustodiaRepository custodiaRepository,
                              CustodiaService custodiaService, MovimientoRepository movimientoRepository,
                              Acreditador acreditador, NominaRepository nominaRepository) {
        this.torneoRepository = torneoRepository;
        this.inscripcionRepository = inscripcionRepository;
        this.premioRepository = premioRepository;
        this.gastoRepository = gastoRepository;
        this.rifaRepository = rifaRepository;
        this.custodiaRepository = custodiaRepository;
        this.custodiaService = custodiaService;
        this.movimientoRepository = movimientoRepository;
        this.acreditador = acreditador;
        this.nominaRepository = nominaRepository;
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
        // un honorario solo es deducible si se depositó en la custodia (HU-604); los gastos comunes, siempre
        List<Gasto> honorarios = gastoRepository.honorariosEnCustodia(custodia.getId());
        BigDecimal depositado = honorarios.stream().map(g -> g.getMovimiento().getMonto())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gastos = gastoRepository.totalComunes(torneoId).add(depositado);
        BigDecimal alOrganizador = gastos.min(retenido.recaudado());

        List<Acreditacion> acreditaciones = new ArrayList<>(entreLosGanadores(torneo,
                retenido.recaudado().subtract(alOrganizador), ref,
                puesto -> "Premio del " + puesto + ".º puesto en " + nombre,
                (puesto, pct, equipo) -> "Tu parte (" + pct + " %) del premio del " + puesto + ".º puesto en " + nombre
                        + ", equipo " + equipo,
                "Liquidación del torneo " + nombre + ": gastos del torneo"));
        if (alOrganizador.signum() > 0) {
            acreditaciones.add(new Acreditacion(Rol.ORGANIZADOR, torneo.getOrganizador().getId(), alOrganizador, null,
                    null, ref + "-organizador", "Liquidación del torneo " + nombre + ": gastos del torneo"));
        }
        if (retenido.gastosAdministrativos().signum() > 0) {
            acreditaciones.add(new Acreditacion(Rol.PLATAFORMA, null, retenido.gastosAdministrativos(), null, null,
                    ref + "-plataforma", "Liquidación del torneo " + nombre + ": gastos administrativos"));
        }
        // lo que el organizador depositó para cada árbitro sale de la custodia hacia él
        for (Gasto honorario : honorarios) {
            acreditaciones.add(new Acreditacion(Rol.HONORARIO, honorario.getBeneficiario().getId(),
                    honorario.getMovimiento().getMonto(), null, null,
                    ref + "-honorario-" + honorario.getId().getGastoId(), "Honorario arbitral en " + nombre));
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

        // las rifas ya liquidadas: su aporte al pozo esperaba a que terminara el torneo
        rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneoId).stream()
                .filter(rifa -> rifa.getEstado() == EstadoRifa.LIQUIDADA)
                .forEach(rifa -> pagarAporteDeLaRifa(rifa.getId()));
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
                (puesto, pct, equipo) -> "Tu parte (" + pct + " %) de lo que aporta la rifa " + rifaTitulo
                        + " al premio del " + puesto + ".º puesto en " + torneoNombre + ", equipo " + equipo,
                "Liquidación del torneo " + torneoNombre + ": lo que aporta la rifa " + rifaTitulo);
        Long torneoId = torneo.getId();
        custodiaService.liberarRifa(rifaId); // deja la sesión vacía
        acreditador.acreditar(Evento.aporte(torneoId, rifaId), acreditaciones);
        return true;
    }

    /**
     * Un pozo entre los que terminaron en un puesto con premio. Lo de un equipo se divide entre los integrantes de su
     * nómina según su reparto (CR-05); un equipo sin reparto (inscripto antes del cambio) cobra todo el capitán. Sin
     * ningún ganador, va al organizador con la referencia {@code ref + "-organizador"}.
     *
     * @param descripcionDelPremio lo que dice el movimiento de quien cobra todo el premio
     * @param descripcionDeLaParte lo que dice el de un integrante que cobra su parte: puesto, su % y el equipo
     */
    private List<Acreditacion> entreLosGanadores(Torneo torneo, BigDecimal pozo, String ref,
                                                 IntFunction<String> descripcionDelPremio,
                                                 DescripcionDeLaParte descripcionDeLaParte,
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
            List<Nomina> nomina = inscripcion.esDeEquipo()
                    ? nominaRepository.findAllByIdInscripcionId(inscripcion.getId()) : List.of();
            if (nomina.isEmpty() || nomina.stream().anyMatch(n -> n.getPctPremio() == null)) {
                acreditaciones.add(new Acreditacion(Rol.PREMIO, inscripcion.titular().getId(), cobro.monto(),
                        inscripcion.getId(), cobro.puesto(), ref + "-premio-" + inscripcion.getId(),
                        descripcionDelPremio.apply(cobro.puesto())));
            } else {
                acreditaciones.addAll(partesDelEquipo(inscripcion, nomina, cobro, ref, descripcionDeLaParte));
            }
        }
        return acreditaciones;
    }

    /**
     * Lo que gana un equipo, entre sus integrantes: cada uno su % redondeado hacia abajo al centavo, y los centavos
     * que sobran al capitán, así lo repartido suma exactamente lo que ganó el equipo. Quien tiene 0 % no cobra.
     */
    private List<Acreditacion> partesDelEquipo(Inscripcion inscripcion, List<Nomina> nomina,
                                               RepartoPorPuesto.Cobro cobro, String ref,
                                               DescripcionDeLaParte descripcion) {
        Long capitanId = inscripcion.titular().getId();
        Map<Long, BigDecimal> montos = new TreeMap<>();
        BigDecimal repartido = BigDecimal.ZERO;
        for (Nomina fila : nomina) {
            BigDecimal parte = cobro.monto().multiply(fila.getPctPremio()).divide(RepartoPremio.CIEN, 2, RoundingMode.DOWN);
            montos.put(fila.getId().getUsuarioId(), parte);
            repartido = repartido.add(parte);
        }
        montos.merge(capitanId, cobro.monto().subtract(repartido), BigDecimal::add);

        String equipo = inscripcion.getEquipo().getNombre();
        List<Acreditacion> partes = new ArrayList<>();
        for (Nomina fila : nomina) {
            Long usuarioId = fila.getId().getUsuarioId();
            BigDecimal monto = montos.get(usuarioId);
            if (monto.signum() > 0) {
                partes.add(new Acreditacion(Rol.PREMIO, usuarioId, monto, inscripcion.getId(), cobro.puesto(),
                        ref + "-premio-" + inscripcion.getId() + "-u" + usuarioId,
                        descripcion.de(cobro.puesto(), porcentaje(fila.getPctPremio()), equipo)));
            }
        }
        return partes;
    }

    /** 40 o 33,34: sin ceros de más y con coma decimal. */
    private static String porcentaje(BigDecimal pct) {
        return pct.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** El texto del movimiento de un integrante que cobra su parte del premio de un puesto. */
    @FunctionalInterface
    private interface DescripcionDeLaParte {
        String de(int puesto, String pct, String equipo);
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
     * Lo que entró a una custodia: las inscripciones, los números vendidos, los gastos administrativos, los honorarios
     * depositados y el total, con
     * cualquier otro movimiento que la liquidación no sepa repartir.
     */
    static Retenido retenido(MovimientoRepository movimientoRepository, Long custodiaId) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal recaudado = BigDecimal.ZERO;
        BigDecimal vendido = BigDecimal.ZERO;
        BigDecimal gastos = BigDecimal.ZERO;
        BigDecimal honorarios = BigDecimal.ZERO;
        for (Object[] fila : movimientoRepository.totalesDeLaCustodia(custodiaId)) {
            TipoMovimiento tipo = (TipoMovimiento) fila[0];
            BigDecimal suma = (BigDecimal) fila[1];
            total = total.add(suma);
            switch (tipo) {
                case INSCRIPCION -> recaudado = recaudado.add(suma);
                case COMPRA_NUMERO -> vendido = vendido.add(suma);
                case COMISION -> gastos = gastos.add(suma);
                case PAGO_SERVICIO -> honorarios = honorarios.add(suma);
                default -> {
                    // queda en el total y no en las partes: la liquidación no cuadra y no paga
                }
            }
        }
        return new Retenido(total, recaudado, vendido, gastos, honorarios);
    }

    /** @param honorarios lo que el organizador depositó para los árbitros (HU-604) */
    record Retenido(BigDecimal total, BigDecimal recaudado, BigDecimal vendido, BigDecimal gastosAdministrativos,
                    BigDecimal honorarios) {
    }
}
