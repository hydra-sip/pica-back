package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.BilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.dto.MovimientoBilleteraResponse;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consulta de saldos y movimientos de la billetera virtual del usuario (HU-602, T602-1).
 */
@Service
@RequiredArgsConstructor
public class BilleteraService {

    private final BilleteraRepository billeteraRepository;
    private final MovimientoRepository movimientoRepository;
    private final TicketRepository ticketRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional
    public BilleteraResponse obtenerBilletera(Long usuarioId) {
        Billetera billetera = obtenerOCrearBilletera(usuarioId);
        return BilleteraResponse.de(billetera);
    }

    @Transactional
    public Page<MovimientoBilleteraResponse> listarMovimientos(Long usuarioId, Pageable pageable) {
        Billetera billetera = obtenerOCrearBilletera(usuarioId);

        Pageable ordenado = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort().isSorted() ? pageable.getSort() : Sort.by(Sort.Direction.DESC, "creadoEn", "id"));

        Page<Movimiento> pagina = movimientoRepository.findByBilleteraId(billetera.getId(), ordenado);

        List<Long> movimientoIds = pagina.getContent().stream()
                .map(Movimiento::getId)
                .toList();

        Map<Long, Rifa> rifasPorMovimiento = movimientoIds.isEmpty()
                ? Collections.emptyMap()
                : ticketRepository.findByMovimientoIdIn(movimientoIds).stream()
                        .collect(Collectors.toMap(
                                t -> t.getMovimiento().getId(),
                                Ticket::getRifa,
                                (r1, r2) -> r1));

        return pagina.map(m -> MovimientoBilleteraResponse.de(m, rifasPorMovimiento.get(m.getId())));
    }

    private Billetera obtenerOCrearBilletera(Long usuarioId) {
        return billeteraRepository.findByUsuarioId(usuarioId).orElseGet(() -> {
            Billetera nueva = new Billetera();
            nueva.setUsuario(usuarioRepository.getReferenceById(usuarioId));
            nueva.setSaldoDisponible(BigDecimal.ZERO);
            nueva.setSaldoRetenido(BigDecimal.ZERO);
            nueva.setGananciaAcumulada(BigDecimal.ZERO);
            nueva.setMoneda("ARS");
            nueva.setEstado(EstadoBilletera.ACTIVA);
            return billeteraRepository.save(nueva);
        });
    }
}

