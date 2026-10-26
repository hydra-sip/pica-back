package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.ConteoInscripciones;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.PremioDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository.GastosPorTorneo;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo y ficha pública de torneos (HU-208). El pozo estimado es el pozo neto con el cupo completo, como en el
 * paso 3 del asistente (HU-601, PICA-270): sale de {@link CuentasTorneo}, así el organizador y el público ven el mismo
 * número.
 */
@Service
@Transactional(readOnly = true)
public class TorneoPublicoService {

    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final PremioRepository premioRepository;
    private final GastoRepository gastoRepository;

    public TorneoPublicoService(
            TorneoRepository torneoRepository,
            InscripcionRepository inscripcionRepository,
            PremioRepository premioRepository,
            GastoRepository gastoRepository) {
        this.torneoRepository = torneoRepository;
        this.inscripcionRepository = inscripcionRepository;
        this.premioRepository = premioRepository;
        this.gastoRepository = gastoRepository;
    }

    public Page<TorneoResumen> listarPublicos(String disciplina, EstadoTorneo estado, Pageable pageable) {
        Specification<Torneo> spec = Specification.where(TorneoSpecifications.noEliminado())
                .and(TorneoSpecifications.noBorrador())
                .and(TorneoSpecifications.conDisciplina(disciplina))
                .and(TorneoSpecifications.conEstado(estado));

        Page<Torneo> pagina = torneoRepository.findAll(spec, pageable);
        if (pagina.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Long> torneoIds = pagina.getContent().stream().map(Torneo::getId).toList();

        Map<Long, Long> confirmadasPorTorneo = inscripcionRepository
                .contarPorTorneoIdInYEstado(torneoIds, EstadoInscripcion.CONFIRMADA)
                .stream()
                .collect(Collectors.toMap(ConteoInscripciones::getTorneoId, ConteoInscripciones::getCantidad));

        Map<Long, BigDecimal> gastosPorTorneo = gastoRepository.totalesPorTorneo(torneoIds).stream()
                .collect(Collectors.toMap(GastosPorTorneo::getTorneoId, GastosPorTorneo::getTotal));

        List<TorneoResumen> resumenes = pagina.getContent().stream().map(torneo -> {
            long confirmadas = confirmadasPorTorneo.getOrDefault(torneo.getId(), 0L);
            int cupoDisponible = Math.max(0, torneo.getCupoMaximo() - (int) confirmadas);
            CuentasTorneo cuentas = cuentas(torneo, gastosPorTorneo.getOrDefault(torneo.getId(), BigDecimal.ZERO));
            return TorneoResumen.de(torneo, cupoDisponible, cuentas.pozoNeto());
        }).toList();

        return new PageImpl<>(resumenes, pageable, pagina.getTotalElements());
    }

    public TorneoDetalle detallePublico(String slug) {
        Torneo torneo = torneoRepository.findBySlugAndEliminadoEnIsNull(slug)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "Torneo no encontrado"));

        if (torneo.getEstado() == EstadoTorneo.BORRADOR) {
            throw new NoEncontradoException(CodigoError.TORNEO_NO_ENCONTRADO, "Torneo no encontrado");
        }

        long confirmadas = inscripcionRepository.countByTorneoIdAndEstado(torneo.getId(), EstadoInscripcion.CONFIRMADA);
        int cupoDisponible = Math.max(0, torneo.getCupoMaximo() - (int) confirmadas);

        CuentasTorneo cuentas = cuentas(torneo, gastoRepository.totalDelTorneo(torneo.getId()));
        List<PremioDetalle> premios = premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId())
                .stream()
                .map(p -> PremioDetalle.de(p, cuentas.delPuesto(p.getPct())))
                .toList();

        return TorneoDetalle.de(torneo, cupoDisponible, cuentas.pozoNeto(), cuentas.premioRifa(), premios);
    }

    /** Con el cupo completo, como el resumen del paso 3. */
    private static CuentasTorneo cuentas(Torneo torneo, BigDecimal gastos) {
        return CuentasTorneo.de(torneo, torneo.getCupoMaximo(), gastos);
    }
}
