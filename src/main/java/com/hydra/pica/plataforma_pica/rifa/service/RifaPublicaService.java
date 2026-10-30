package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.common.config.RifaConfig.RifaProperties;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.NumeroOcupado;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaResumen;
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

    private Rifa buscarPublica(Long id) {
        return rifaRepository.findByIdAndEliminadoEnIsNull(id)
                .filter(r -> r.getEstado() != EstadoRifa.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + id));
    }
}
