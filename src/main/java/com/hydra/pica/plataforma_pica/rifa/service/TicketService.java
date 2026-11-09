package com.hydra.pica.plataforma_pica.rifa.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.TicketDetalle;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El ticket digital (HU-404, PICA-224). Se emite al confirmar la compra ({@link VentaRifaService#comprar}): una
 * reserva todavía no tiene ticket. Lo ve su comprador y quien tiene RIFA_ADMINISTRAR; para cualquier otro no existe
 * (404, igual que uno que no está).
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    static final String PERMISO_ADMINISTRAR = "RIFA_ADMINISTRAR";

    private final TicketRepository ticketRepository;
    private final NumeroRepository numeroRepository;
    private final PremioRifaRepository premioRifaRepository;
    private final MovimientoRepository movimientoRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public TicketDetalle detalle(Long usuarioId, Long id) {
        Ticket ticket = ticketRepository.findById(id)
                .filter(t -> t.getComprador().getId().equals(usuarioId)
                        || currentUserProvider.getPermisos().contains(PERMISO_ADMINISTRAR))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.TICKET_NO_ENCONTRADO,
                        "No existe el ticket " + id));
        return detalle(ticket, usuarioId);
    }

    /** Los del usuario, el más nuevo primero. */
    @Transactional(readOnly = true)
    public List<TicketDetalle> misTickets(Long usuarioId) {
        return ticketRepository.findByCompradorIdOrderByIdDesc(usuarioId).stream()
                .map(ticket -> detalle(ticket, usuarioId))
                .toList();
    }

    private TicketDetalle detalle(Ticket ticket, Long usuarioId) {
        List<Integer> numeros = numeros(ticket);
        List<PremioRifaDetalle> ganados = premioRifaRepository.buscarPorRifa(ticket.getRifa().getId()).stream()
                .filter(p -> p.getNumeroGanador() != null && numeros.contains(p.getNumeroGanador()))
                .map(PremioRifaDetalle::de)
                .toList();
        return TicketDetalle.de(ticket, numeros, usuarioId,
                ReclamoRifaService.ventanaAbierta(ticket.getRifa(), Instant.now()), ganados,
                premioAcreditado(ticket.getRifa(), ganados));
    }

    /** Los premios que le acreditó la liquidación (HU-606), con las referencias que usa: liq-r{rifa}-premio-{puesto}. */
    private BigDecimal premioAcreditado(Rifa rifa, List<PremioRifaDetalle> ganados) {
        if (ganados.isEmpty() || rifa.getEstado() != EstadoRifa.LIQUIDADA) {
            return null;
        }
        return movimientoRepository.sumaDeReferencias(ganados.stream()
                .map(p -> "liq-r" + rifa.getId() + "-premio-" + p.puesto())
                .toList());
    }

    private List<Integer> numeros(Ticket ticket) {
        return numeroRepository.findByTicketIdOrderByIdNumero(ticket.getId()).stream()
                .map(n -> n.getId().getNumero())
                .toList();
    }
}
