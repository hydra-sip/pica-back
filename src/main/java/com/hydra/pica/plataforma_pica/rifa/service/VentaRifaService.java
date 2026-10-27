package com.hydra.pica.plataforma_pica.rifa.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.event.NumerosVendidos;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.torneo.service.PozoTorneoService;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servicio de venta de números de rifa (HU-305, T305-1).
 * Cobra por billetera/cobroService, emite el ticket, persiste los números como VENDIDO,
 * calcula los nuevos pozos y publica el evento NumerosVendidos para WebSocket.
 */
@Service
@RequiredArgsConstructor
public class VentaRifaService {

    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;
    private final TicketRepository ticketRepository;
    private final UsuarioRepository usuarioRepository;
    private final CobroService cobroService;
    private final PozoTorneoService pozoTorneoService;
    private final ApplicationEventPublisher eventos;

    @Transactional
    public VentaRifaResultado vender(Long compradorId, Long rifaId, List<Integer> numeros, String claveIdempotencia) {
        Rifa rifa = rifaRepository.findById(rifaId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + rifaId));

        if (rifa.getEstado() != EstadoRifa.EN_VENTA) {
            throw new ConflictoException(CodigoError.RIFA_NO_EDITABLE,
                    "La rifa " + rifaId + " está " + rifa.getEstado() + ": solo se compran números en EN_VENTA");
        }

        if (numeros == null || numeros.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Debe seleccionar al menos un número");
        }

        Set<Integer> sinRepetir = new HashSet<>(numeros);
        if (sinRepetir.size() != numeros.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "La lista contiene números duplicados");
        }

        for (int n : numeros) {
            if (n < rifa.getNumeroDesde() || n > rifa.getNumeroHasta()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION,
                        "El número " + n + " no pertenece al rango [" + rifa.getNumeroDesde() + ", " + rifa.getNumeroHasta() + "]");
            }
        }

        List<Numero> yaVendidos = numeroRepository.findByIdRifaIdAndIdNumeroIn(rifaId, numeros)
                .stream()
                .filter(num -> num.getEstado() == EstadoNumero.VENDIDO)
                .toList();

        if (!yaVendidos.isEmpty()) {
            List<Integer> ocupados = yaVendidos.stream().map(num -> num.getId().getNumero()).toList();
            throw new ConflictoException(CodigoError.NUMERO_YA_VENDIDO,
                    "Los siguientes números ya fueron vendidos: " + ocupados);
        }

        Usuario comprador = usuarioRepository.findById(compradorId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + compradorId));

        BigDecimal montoTotal = rifa.getPrecioNumero().multiply(BigDecimal.valueOf(numeros.size()));
        String clave = (claveIdempotencia != null && !claveIdempotencia.isBlank())
                ? claveIdempotencia
                : "ticket-" + UUID.randomUUID();

        Long torneoId = rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;

        Movimiento pago = cobroService.cobrar(new SolicitudCobro(
                compradorId,
                TipoMovimiento.COMPRA_NUMERO,
                montoTotal,
                torneoId,
                null,
                clave,
                "Compra de " + numeros.size() + " números de la rifa " + rifa.getTitulo()
        ));

        Ticket ticket = new Ticket();
        ticket.setCodigo("T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        ticket.setRifa(rifa);
        ticket.setComprador(comprador);
        ticket.setMovimiento(pago);
        ticket.setMonto(montoTotal);
        ticket.setComision(BigDecimal.ZERO);
        ticket.setMoneda(rifa.getMoneda() != null ? rifa.getMoneda() : "ARS");
        ticket.setClaveIdempotencia(clave);
        ticket = ticketRepository.save(ticket);

        for (int num : numeros) {
            Numero nuevo = Numero.vendido(rifa, num, ticket);
            numeroRepository.save(nuevo);
        }

        long totalVendidos = numeroRepository.countByIdRifaIdAndEstado(rifaId, EstadoNumero.VENDIDO);
        BigDecimal pozoRifa = rifa.getPrecioNumero().multiply(BigDecimal.valueOf(totalVendidos));
        BigDecimal pozoTorneo = torneoId != null ? pozoTorneoService.calcularPozo(torneoId) : null;

        eventos.publishEvent(new NumerosVendidos(
                rifaId,
                torneoId,
                numeros,
                compradorId,
                montoTotal,
                pozoRifa,
                pozoTorneo
        ));

        return new VentaRifaResultado(
                ticket.getId(),
                ticket.getCodigo(),
                numeros,
                montoTotal,
                pozoRifa,
                pozoTorneo
        );
    }
}
