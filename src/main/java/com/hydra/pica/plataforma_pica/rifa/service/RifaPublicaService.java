package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.common.config.RifaConfig.RifaProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.GanadorRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.LineaListaSellada;
import com.hydra.pica.plataforma_pica.rifa.dto.NumeroOcupado;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaResumen;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaSorteadaResumen;
import com.hydra.pica.plataforma_pica.rifa.dto.SorteoRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las rifas como las ve quien compra (HU-403, PICA-221), sin login: las que están en venta, el detalle y la grilla
 * de números. Un borrador no existe para el público (404, como una dada de baja).
 *
 * La grilla no guarda los libres: un número tiene fila desde que alguien lo reserva (V13), así que se devuelven
 * los ocupados y el front arma el rango. Una reserva vencida cuenta como libre.
 */
@Service
@RequiredArgsConstructor
public class RifaPublicaService {

    private static final Set<EstadoRifa> SORTEADAS =
            EnumSet.of(EstadoRifa.SORTEADA, EstadoRifa.EN_REVISION, EstadoRifa.LIQUIDADA);

    private final RifaRepository rifaRepository;
    private final PremioRifaRepository premioRifaRepository;
    private final NumeroRepository numeroRepository;
    private final RifaProperties rifaProperties;

    /**
     * En venta y con la venta sin terminar, también las que todavía no empezaron. Primero las que cierran antes; el
     * orden no se elige desde afuera.
     */
    @Transactional(readOnly = true)
    public Page<RifaPublicaResumen> listarEnVenta(Pageable pageable) {
        Pageable porCierre = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by("ventaHasta", "id"));
        return rifaRepository.findByEstadoAndVentaHastaAfterAndEliminadoEnIsNull(EstadoRifa.EN_VENTA, Instant.now(),
                porCierre).map(RifaPublicaResumen::de);
    }

    @Transactional(readOnly = true)
    public RifaPublicaDetalle detalle(Long id) {
        Rifa rifa = buscarPublica(id);
        return RifaPublicaDetalle.de(rifa, premioRifaRepository.buscarPorRifa(rifa.getId()),
                numeroRepository.countByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO));
    }

    @Transactional(readOnly = true)
    public List<NumeroOcupado> numerosOcupados(Long id) {
        Rifa rifa = buscarPublica(id);
        Instant ahora = Instant.now();
        return numeroRepository.findByIdRifaIdOrderByIdNumero(rifa.getId()).stream()
                .filter(n -> !n.reservaVencida(ahora, rifaProperties.duracionReserva()))
                .map(NumeroOcupado::de)
                .toList();
    }

    /**
     * El desglose de {@code listaHash}: los vendidos al cerrar la venta con el código de su ticket, por número, para
     * recalcular el sello. Sin la lista sellada todavía, 404.
     */
    @Transactional(readOnly = true)
    public List<LineaListaSellada> listaSellada(Long id) {
        Rifa rifa = buscarPublica(id);
        if (rifa.getListaSelladaEn() == null) {
            throw new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA,
                    "La rifa " + id + " todavía no cerró la venta: no tiene lista sellada");
        }
        return numeroRepository.findByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO).stream()
                .map(LineaListaSellada::de)
                .sorted(Comparator.comparingInt(LineaListaSellada::numero))
                .toList();
    }

    /** Las sorteadas, también las que están en revisión o ya liquidadas (HU-504): la última sorteada primero. */
    @Transactional(readOnly = true)
    public Page<RifaSorteadaResumen> listarSorteadas(Pageable pageable) {
        Pageable porSorteo = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "semillaObtenidaEn", "id"));
        return rifaRepository.findByEstadoInAndEliminadoEnIsNull(SORTEADAS, porSorteo).map(RifaSorteadaResumen::de);
    }

    /** Los datos para recalcular el sorteo (HU-504); antes del sorteo, 409 RIFA_NO_SORTEADA. */
    @Transactional(readOnly = true)
    public SorteoRifaDetalle sorteo(Long id) {
        Rifa rifa = buscarPublica(id);
        if (!SORTEADAS.contains(rifa.getEstado())) {
            throw new ConflictoException(CodigoError.RIFA_NO_SORTEADA,
                    "La rifa " + id + " está " + rifa.getEstado() + ": todavía no se sorteó");
        }
        // el username de quien compró cada número, para mostrar a los ganadores
        Map<Integer, String> compradores = numeroRepository.findByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO)
                .stream()
                .collect(Collectors.toMap(n -> n.getId().getNumero(), n -> n.getTicket().getComprador().getUsername()));
        List<GanadorRifa> premios = premioRifaRepository.buscarPorRifa(rifa.getId()).stream()
                .map(p -> GanadorRifa.de(p, p.getNumeroGanador() == null ? null : compradores.get(p.getNumeroGanador())))
                .toList();
        return SorteoRifaDetalle.de(rifa, listaSellada(id), premios);
    }

    private Rifa buscarPublica(Long id) {
        return rifaRepository.findByIdAndEliminadoEnIsNull(id)
                .filter(r -> r.getEstado() != EstadoRifa.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + id));
    }
}
