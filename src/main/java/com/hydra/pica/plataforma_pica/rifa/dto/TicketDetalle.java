package com.hydra.pica.plataforma_pica.rifa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

/**
 * El ticket digital de una compra (HU-404): la numeración y el comprobante. {@code monto} es lo que fue al pozo de la
 * rifa y {@code comision} los gastos administrativos; el total pagado es la suma. {@code sorteadaEn} es la fecha
 * programada del sorteo. {@code ventanaReclamoAbierta} es de los reclamos (HU-506) y por ahora va en false.
 */
public record TicketDetalle(
        Long id,
        String codigo,
        Long rifaId,
        String rifaTitulo,
        Long torneoId,
        String torneoNombre,
        Long compradorId,
        String compradorNombre,
        String compradorUsername,
        List<Integer> numeros,
        BigDecimal monto,
        BigDecimal comision,
        String moneda,
        Instant creadoEn,
        EstadoRifa rifaEstado,
        Instant sorteadaEn,
        boolean esComprador,
        boolean ventanaReclamoAbierta) {

    /** {@code usuarioId}: quien lo pide; un administrador ve el ticket con {@code esComprador} en false. */
    public static TicketDetalle de(Ticket ticket, List<Integer> numeros, Long usuarioId) {
        Rifa rifa = ticket.getRifa();
        Usuario comprador = ticket.getComprador();
        return new TicketDetalle(ticket.getId(), ticket.getCodigo(), rifa.getId(), rifa.getTitulo(),
                rifa.getTorneo() != null ? rifa.getTorneo().getId() : null,
                rifa.getTorneo() != null ? rifa.getTorneo().getNombre() : null,
                comprador.getId(), comprador.getPersona().getNombres() + " " + comprador.getPersona().getApellidos(),
                comprador.getUsername(), numeros, ticket.getMonto(), ticket.getComision(), ticket.getMoneda(),
                ticket.getCreadoEn(), rifa.getEstado(), rifa.getSorteadaEn(), comprador.getId().equals(usuarioId),
                false);
    }
}
