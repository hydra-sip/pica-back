package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion.Linea;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion.Origen;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El reparto de la liquidación para el organizador (HU-606, wireframe 64): a quién se le acreditó qué, leído de los
 * movimientos por su referencia. El del torneo incluye lo que le aportó su rifa; el de la rifa, lo que ella aportó al
 * torneo. De otro organizador, 404 como si no existiera.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepartoLiquidacionService {

    private static final String MONEDA = "ARS";

    private final TorneoRepository torneoRepository;
    private final RifaRepository rifaRepository;
    private final CustodiaRepository custodiaRepository;
    private final MovimientoRepository movimientoRepository;

    public RepartoLiquidacion delTorneo(Long usuarioId, Long torneoId) {
        Torneo torneo = torneoRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(torneoId, usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO,
                        "No existe el torneo " + torneoId));
        return delTorneo(torneo);
    }

    /** Para quien ya sabe que puede verlo: el panel financiero también lo abre el admin (HU-608). */
    RepartoLiquidacion delTorneo(Torneo torneo) {
        Long torneoId = torneo.getId();
        Instant liquidadoEn = custodiaRepository.findByTorneoId(torneo.getId())
                .filter(c -> c.getEstado() == EstadoCustodia.LIBERADA)
                .map(Custodia::getLiberadaEn)
                .orElse(null);
        List<Movimiento> movimientos = new ArrayList<>(movimientoRepository.deLaLiquidacion("liq-t" + torneoId + "-%"));
        rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneoId).forEach(rifa -> movimientos
                .addAll(movimientoRepository.deLaLiquidacion("liq-r" + rifa.getId() + "-aporte-%")));
        return reparto(liquidadoEn != null, liquidadoEn, movimientos);
    }

    public RepartoLiquidacion deLaRifa(Long usuarioId, Long rifaId) {
        Rifa rifa = rifaRepository.findByIdAndOrganizadorIdAndEliminadoEnIsNull(rifaId, usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA,
                        "No existe la rifa " + rifaId));
        List<Movimiento> movimientos = movimientoRepository.deLaLiquidacion("liq-r" + rifaId + "-%");
        // la rifa no guarda cuándo se liquidó: es cuando se acreditó lo suyo (el aporte puede llegar después)
        Instant liquidadaEn = movimientos.stream()
                .filter(m -> !m.getReferenciaExterna().contains("-aporte-"))
                .map(Movimiento::getCreadoEn)
                .min(Comparator.naturalOrder())
                .orElse(null);
        boolean liquidada = rifa.getEstado() == EstadoRifa.LIQUIDADA;
        return reparto(liquidada, liquidada ? liquidadaEn : null, movimientos);
    }

    private static RepartoLiquidacion reparto(boolean liquidado, Instant liquidadoEn, List<Movimiento> movimientos) {
        List<Linea> lineas = movimientos.stream()
                .sorted(Comparator.comparing(Movimiento::getId))
                .map(RepartoLiquidacionService::linea)
                .toList();
        BigDecimal total = lineas.stream().map(Linea::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RepartoLiquidacion(liquidado, liquidadoEn, total, MONEDA, lineas);
    }

    /** Lo que dice su referencia: liq-t{id}-… (con -honorario-{gastoId} para un árbitro, HU-604), liq-r{id}-premio-{puesto} o liq-r{id}-aporte-…. */
    private static Linea linea(Movimiento movimiento) {
        String referencia = movimiento.getReferenciaExterna();
        Usuario usuario = movimiento.getBilletera().getUsuario();
        Rol rol = movimiento.getTipo() == TipoMovimiento.PREMIO ? Rol.PREMIO
                : movimiento.getTipo() == TipoMovimiento.HONORARIO ? Rol.HONORARIO
                : usuario == null ? Rol.PLATAFORMA : Rol.ORGANIZADOR;
        Origen origen = referencia.contains("-aporte-") ? Origen.APORTE_RIFA
                : referencia.startsWith("liq-t") ? Origen.TORNEO : Origen.RIFA;

        Inscripcion inscripcion = movimiento.getInscripcion();
        Integer puesto = null;
        if (inscripcion != null) {
            puesto = inscripcion.getPosicion();
        } else if (rol == Rol.PREMIO && origen == Origen.RIFA) {
            puesto = Integer.valueOf(referencia.substring(referencia.lastIndexOf('-') + 1));
        }
        return new Linea(rol, origen, puesto,
                usuario == null ? null : usuario.getPersona().getNombres() + " " + usuario.getPersona().getApellidos(),
                usuario == null ? null : usuario.getUsername(),
                inscripcion != null && inscripcion.getEquipo() != null ? inscripcion.getEquipo().getNombre() : null,
                movimiento.getMonto(), movimiento.getCreadoEn());
    }
}
