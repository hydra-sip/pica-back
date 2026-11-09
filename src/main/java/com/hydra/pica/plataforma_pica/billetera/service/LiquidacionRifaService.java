package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditador.Evento;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.service.ReclamoRifaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La liquidación de una rifa sorteada (HU-606, PICA-330), al vencer el plazo de reclamo sin reclamos pendientes.
 * <ul>
 * <li>Lo vendido se reparte como se publicó ({@link RepartoRifa}): el % destinado al torneo va a su pozo y el resto a
 * los premios, por puesto, a quien compró el número ganador. Lo de un premio sin ganador (más premios que números
 * vendidos) se reparte entre los que tienen ({@link RepartoPorPuesto}).</li>
 * <li>Los gastos administrativos van a la plataforma.</li>
 * <li>Lo que va al pozo del torneo se paga a los ganadores del torneo cuando terminaron los dos: si el torneo ya se
 * liquidó, ahora; si no, queda en la custodia de la rifa y lo paga la liquidación del torneo.</li>
 * </ul>
 * La rifa pasa a LIQUIDADA, que es la marca: un reintento da 409 CUSTODIA_LIBERADA. Lo repartido tiene que dar lo
 * retenido, como en el torneo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiquidacionRifaService {

    static final String SIN_TERMINAR =
            "La rifa debe estar sorteada y con el plazo de reclamo vencido para liberar la custodia";

    private final RifaRepository rifaRepository;
    private final PremioRifaRepository premioRifaRepository;
    private final NumeroRepository numeroRepository;
    private final ReclamoRifaRepository reclamoRifaRepository;
    private final CustodiaRepository custodiaRepository;
    private final CustodiaService custodiaService;
    private final MovimientoRepository movimientoRepository;
    private final LiquidacionService liquidacionService;
    private final Acreditador acreditador;

    /**
     * @param aporteAlTorneo   lo que va al pozo del torneo
     * @param aportePendiente  si espera a que se liquide el torneo, en la custodia de la rifa
     */
    public record LiquidacionRifa(Long rifaId, Long custodiaId, BigDecimal retenido, List<Acreditacion> acreditaciones,
                                  BigDecimal aporteAlTorneo, boolean aportePendiente) {
    }

    @Transactional
    public LiquidacionRifa liquidarRifa(Long rifaId) {
        return liquidarRifa(rifaId, Instant.now());
    }

    /**
     * Sin sortear, con el plazo abierto, con un reclamo pendiente (EN_REVISION) o cancelada: 409 FONDOS_EN_CUSTODIA.
     * Ya liquidada: 409 CUSTODIA_LIBERADA.
     */
    @Transactional
    public LiquidacionRifa liquidarRifa(Long rifaId, Instant ahora) {
        Rifa rifa = rifaRepository.buscarBloqueado(rifaId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + rifaId));
        if (rifa.getEstado() == EstadoRifa.LIQUIDADA) {
            throw new ConflictoException(CodigoError.CUSTODIA_LIBERADA, "La rifa " + rifaId + " ya se liquidó")
                    .con("estado", custodiaRepository.findByRifaId(rifaId).map(Custodia::getEstado)
                            .orElse(EstadoCustodia.LIBERADA));
        }
        Instant plazo = ReclamoRifaService.plazoReclamo(rifa);
        if (rifa.getEstado() != EstadoRifa.SORTEADA || plazo == null || !ahora.isAfter(plazo)
                || reclamoRifaRepository.existsByRifaIdAndEstado(rifaId, EstadoReclamo.PENDIENTE)) {
            throw new FondosEnCustodiaException(SIN_TERMINAR);
        }
        custodiaRepository.crearDeLaRifa(rifaId);
        Custodia custodia = custodiaRepository.findParaLiberarPorRifaId(rifaId).orElseThrow();
        if (custodia.getEstado() != EstadoCustodia.RETENIDA) {
            throw new ConflictoException(CodigoError.CUSTODIA_LIBERADA, "La rifa " + rifaId + " ya se liquidó")
                    .con("estado", custodia.getEstado());
        }

        LiquidacionService.Retenido retenido = LiquidacionService.retenido(movimientoRepository, custodia.getId());
        RepartoRifa reparto = RepartoRifa.deLoRecaudado(retenido.vendido(), rifa.getPctDestinadoTorneo());
        String titulo = rifa.getTitulo();
        String ref = "liq-r" + rifaId;
        List<Acreditacion> acreditaciones = new ArrayList<>(premios(rifa, reparto.aPremios(), ref, titulo));
        if (retenido.gastosAdministrativos().signum() > 0) {
            acreditaciones.add(new Acreditacion(Rol.PLATAFORMA, null, retenido.gastosAdministrativos(), null, null,
                    ref + "-plataforma", "Liquidación de la rifa " + titulo + ": gastos administrativos"));
        }
        BigDecimal repartido = Acreditador.suma(acreditaciones).add(reparto.alPozoTorneo());
        if (repartido.compareTo(retenido.total()) != 0) {
            acreditador.alertar(Evento.rifa(rifaId), custodia.getId(), retenido.total(), repartido);
            throw new IllegalStateException("La liquidación de la rifa " + rifaId + " reparte " + repartido
                    + " y la custodia retiene " + retenido.total() + ": no se pagó nada");
        }

        Long custodiaId = custodia.getId();
        Long torneoId = rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;
        rifa.setEstado(EstadoRifa.LIQUIDADA);
        rifaRepository.saveAndFlush(rifa);
        acreditador.acreditar(Evento.rifa(rifaId), acreditaciones);

        boolean pendiente = false;
        if (reparto.alPozoTorneo().signum() == 0) {
            custodiaService.liberarRifa(rifaId);
        } else if (torneoLiquidado(torneoId)) {
            liquidacionService.pagarAporteDeLaRifa(rifaId);
        } else {
            // lo paga la liquidación del torneo; hasta entonces queda en la custodia de la rifa
            pendiente = true;
            log.info("La rifa {} se liquidó; su aporte de {} espera a la liquidación del torneo {}", rifaId,
                    reparto.alPozoTorneo(), torneoId);
        }
        return new LiquidacionRifa(rifaId, custodiaId, retenido.total(), acreditaciones, reparto.alPozoTorneo(),
                pendiente);
    }

    /** A quien compró cada número ganador; sin ningún ganador, al organizador. */
    private List<Acreditacion> premios(Rifa rifa, BigDecimal aPremios, String ref, String titulo) {
        if (aPremios.signum() <= 0) {
            return List.of();
        }
        List<PremioRifa> premios = premioRifaRepository.buscarPorRifa(rifa.getId());
        Map<Integer, Integer> numeroPorPuesto = new HashMap<>();
        premios.stream().filter(p -> p.getNumeroGanador() != null)
                .forEach(p -> numeroPorPuesto.put(p.getId().getPuesto(), p.getNumeroGanador()));
        Map<Integer, Long> compradorPorNumero = numeroRepository
                .findByIdRifaIdAndIdNumeroIn(rifa.getId(), numeroPorPuesto.values()).stream()
                .filter(n -> n.getTicket() != null)
                .collect(Collectors.toMap(n -> n.getId().getNumero(), (Numero n) -> n.getTicket().getComprador().getId()));

        Map<Integer, BigDecimal> pctPorPuesto = premios.stream()
                .collect(Collectors.toMap(p -> p.getId().getPuesto(), p -> BigDecimal.valueOf(p.getPct())));
        List<RepartoPorPuesto.Posicionado> conGanador = numeroPorPuesto.entrySet().stream()
                .filter(e -> compradorPorNumero.containsKey(e.getValue()))
                .map(e -> new RepartoPorPuesto.Posicionado(e.getKey().longValue(), e.getKey()))
                .toList();
        List<RepartoPorPuesto.Cobro> cobros = RepartoPorPuesto.repartir(aPremios, pctPorPuesto, conGanador);
        if (cobros.isEmpty()) {
            return List.of(new Acreditacion(Rol.ORGANIZADOR, rifa.getOrganizador().getId(), aPremios, null, null,
                    ref + "-organizador", "Liquidación de la rifa " + titulo + ": premios sin ganador"));
        }
        return cobros.stream()
                .map(c -> new Acreditacion(Rol.PREMIO, compradorPorNumero.get(numeroPorPuesto.get(c.puesto())),
                        c.monto(), null, c.puesto(), ref + "-premio-" + c.puesto(),
                        "Premio del " + c.puesto() + ".º puesto en la rifa " + titulo))
                .toList();
    }

    private boolean torneoLiquidado(Long torneoId) {
        return torneoId != null && custodiaRepository.findByTorneoId(torneoId)
                .map(c -> c.getEstado() == EstadoCustodia.LIBERADA)
                .orElse(false);
    }
}
