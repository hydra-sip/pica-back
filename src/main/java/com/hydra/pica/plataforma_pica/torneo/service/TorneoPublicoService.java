package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.ConteoInscripciones;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.PremioDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;
import com.hydra.pica.plataforma_pica.torneo.repository.PremioRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TorneoPublicoService {

    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final RifaRepository rifaRepository;
    private final PremioRepository premioRepository;

    public TorneoPublicoService(
            TorneoRepository torneoRepository,
            InscripcionRepository inscripcionRepository,
            RifaRepository rifaRepository,
            PremioRepository premioRepository) {
        this.torneoRepository = torneoRepository;
        this.inscripcionRepository = inscripcionRepository;
        this.rifaRepository = rifaRepository;
        this.premioRepository = premioRepository;
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

        Map<Long, Rifa> rifaPorTorneo = rifaRepository
                .findByTorneoIdInAndEliminadoEnIsNull(torneoIds)
                .stream()
                .filter(r -> r.getTorneo() != null)
                .collect(Collectors.toMap(r -> r.getTorneo().getId(), r -> r, (r1, r2) -> r1));

        List<TorneoResumen> resumenes = pagina.getContent().stream().map(torneo -> {
            long confirmadas = confirmadasPorTorneo.getOrDefault(torneo.getId(), 0L);
            int cupoDisponible = Math.max(0, torneo.getCupoMaximo() - (int) confirmadas);
            Rifa rifa = rifaPorTorneo.get(torneo.getId());
            BigDecimal pozoEstimado = calcularPozoEstimado(torneo, rifa);
            return TorneoResumen.de(torneo, cupoDisponible, pozoEstimado);
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

        Optional<Rifa> rifaOpt = rifaRepository.findByTorneoIdAndEliminadoEnIsNull(torneo.getId());
        BigDecimal pozoEstimado = calcularPozoEstimado(torneo, rifaOpt.orElse(null));

        List<PremioDetalle> premios = premioRepository.findByTorneoIdOrderByIdPuestoAsc(torneo.getId())
                .stream()
                .map(PremioDetalle::de)
                .toList();

        return TorneoDetalle.de(torneo, cupoDisponible, pozoEstimado, premios);
    }

    public BigDecimal calcularPozoEstimado(Torneo torneo, Rifa rifa) {
        BigDecimal baseCompetidores = BigDecimal.ZERO;
        if (torneo.getValorInscripcion() != null && torneo.getCupoMaximo() != null && torneo.getPctPozoCompetidores() != null) {
            baseCompetidores = torneo.getValorInscripcion()
                    .multiply(BigDecimal.valueOf(torneo.getCupoMaximo()))
                    .multiply(torneo.getPctPozoCompetidores())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        BigDecimal aporteRifa = BigDecimal.ZERO;
        if (rifa != null && rifa.getEstado() != EstadoRifa.BORRADOR && rifa.getEstado() != EstadoRifa.CANCELADA
                && torneo.getPctPremioRifa() != null && rifa.getPrecioNumero() != null) {
            int totalNumeros = rifa.getNumeroHasta() - rifa.getNumeroDesde() + 1;
            BigDecimal recaudacionRifa = rifa.getPrecioNumero().multiply(BigDecimal.valueOf(totalNumeros));
            aporteRifa = recaudacionRifa
                    .multiply(torneo.getPctPremioRifa())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }

        return baseCompetidores.add(aporteRifa);
    }
}
