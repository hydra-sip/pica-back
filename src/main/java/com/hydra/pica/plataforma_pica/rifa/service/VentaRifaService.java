package com.hydra.pica.plataforma_pica.rifa.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.config.RifaConfig.RifaProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.RepartoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.dto.ReservaRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.event.NumerosVendidos;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.GastosAdministrativos;
import com.hydra.pica.plataforma_pica.torneo.service.PozoTorneoService;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La venta de números de una rifa (HU-403, PICA-222; empezó en HU-305, T305-1), en dos pasos, como con una pasarela
 * de pago:
 *
 * <ol>
 * <li>Reservar: los números elegidos quedan RESERVADO a nombre del comprador por {@code app.rifas.duracion-reserva}.
 * Un número tiene fila desde que se reserva (V13), así que la PK impide que dos lo reserven a la vez: el segundo
 * recibe 409 NUMERO_NO_DISPONIBLE. Una reserva vencida cuenta como libre y se borra al reservar ese número.</li>
 * <li>Comprar: con los números bloqueados, se cobra por {@link CobroService} el precio más los gastos administrativos
 * que la rifa fijó al publicar (en dos movimientos, COMPRA_NUMERO y COMISION, como la inscripción), se emite el
 * ticket y los números pasan a VENDIDO. Publica {@link NumerosVendidos} para el tiempo real.</li>
 * </ol>
 *
 * Se reserva y se compra solo con la venta abierta (EN_VENTA y entre {@code ventaDesde} y {@code ventaHasta}), y el
 * organizador no compra su propia rifa. La compra es idempotente: con la misma clave devuelve el mismo ticket sin
 * volver a cobrar, y la clave usada para otra compra da 409.
 */
@Service
@RequiredArgsConstructor
public class VentaRifaService {

    /** Sin 0/O ni 1/I, para que el código del ticket se lea sin confundirse. */
    private static final String LETRAS_CODIGO = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom AZAR = new SecureRandom();

    private final RifaRepository rifaRepository;
    private final NumeroRepository numeroRepository;
    private final TicketRepository ticketRepository;
    private final UsuarioRepository usuarioRepository;
    private final CobroService cobroService;
    private final PozoTorneoService pozoTorneoService;
    private final ApplicationEventPublisher eventos;
    private final RifaProperties rifaProperties;

    /** Reemplaza la reserva que el usuario tuviera en la rifa: queda reservado solo lo que eligió ahora. */
    @Transactional
    public ReservaRifa reservar(Long usuarioId, Long rifaId, List<Integer> numeros) {
        Instant ahora = Instant.now();
        Rifa rifa = buscarEnVenta(usuarioId, rifaId, ahora);
        List<Integer> elegidos = validarNumeros(rifa, numeros);

        numeroRepository.liberarDelUsuario(rifaId, usuarioId);
        numeroRepository.liberarVencidas(rifaId, elegidos, ahora.minus(rifaProperties.duracionReserva()));
        List<Integer> ocupados = numeroRepository.findByIdRifaIdAndIdNumeroIn(rifaId, elegidos).stream()
                .map(n -> n.getId().getNumero())
                .sorted()
                .toList();
        if (!ocupados.isEmpty()) {
            throw noDisponibles(ocupados);
        }

        // liberar limpió la sesión: la rifa y el usuario van como referencias
        Rifa referencia = rifaRepository.getReferenceById(rifaId);
        var usuario = usuarioRepository.getReferenceById(usuarioId);
        try {
            numeroRepository.saveAllAndFlush(elegidos.stream().map(n -> Numero.reservado(referencia, n, usuario)).toList());
        } catch (DataIntegrityViolationException e) {
            // otro reservó alguno entre la consulta y el insert
            throw noDisponibles(elegidos);
        }

        BigDecimal monto = rifa.getPrecioNumero().multiply(BigDecimal.valueOf(elegidos.size()));
        BigDecimal gastos = GastosAdministrativos.monto(monto, rifa.getPctGastosAdministrativos());
        return new ReservaRifa(rifaId, elegidos, rifa.getPrecioNumero(), monto, rifa.getPctGastosAdministrativos(),
                gastos, monto.add(gastos), ahora.plus(rifaProperties.duracionReserva()));
    }

    /** Libera los números que el usuario tiene reservados en la rifa; sin reserva no hace nada. */
    @Transactional
    public void cancelarReserva(Long usuarioId, Long rifaId) {
        buscarPublica(rifaId);
        numeroRepository.liberarDelUsuario(rifaId, usuarioId);
    }

    /**
     * Compra los números que el usuario tiene reservados. Si alguno ya no es suyo, 409 NUMERO_NO_DISPONIBLE; si su
     * reserva venció, 409 RESERVA_VENCIDA. En los dos casos no se cobra nada.
     */
    @Transactional
    public VentaRifaResultado comprar(Long compradorId, Long rifaId, List<Integer> numeros, String claveIdempotencia) {
        validarClave(claveIdempotencia);
        Optional<Ticket> previo = ticketRepository.findByClaveIdempotencia(claveIdempotencia);
        if (previo.isPresent()) {
            return repetida(previo.get(), compradorId, rifaId, numeros);
        }
        Instant ahora = Instant.now();
        Rifa rifa = buscarEnVenta(compradorId, rifaId, ahora);
        List<Integer> elegidos = validarNumeros(rifa, numeros);

        List<Numero> bloqueados = numeroRepository.bloquear(rifaId, elegidos);
        // un pedido con la misma clave pudo terminar mientras este esperaba el bloqueo
        previo = ticketRepository.findByClaveIdempotencia(claveIdempotencia);
        if (previo.isPresent()) {
            return repetida(previo.get(), compradorId, rifaId, numeros);
        }
        validarReserva(bloqueados, elegidos, compradorId, ahora);

        BigDecimal monto = rifa.getPrecioNumero().multiply(BigDecimal.valueOf(elegidos.size()));
        BigDecimal gastos = GastosAdministrativos.monto(monto, rifa.getPctGastosAdministrativos());
        Long torneoId = rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;
        Movimiento pago = cobroService.cobrar(new SolicitudCobro(compradorId, TipoMovimiento.COMPRA_NUMERO,
                monto, torneoId, null, claveIdempotencia,
                "Compra de " + elegidos.size() + " números de la rifa " + rifa.getTitulo()));
        // los gastos administrativos van aparte, en otro movimiento, igual que en la inscripción: los números son del
        // pozo de la rifa y esto de la plataforma (PICA-283)
        if (gastos.signum() > 0) {
            cobroService.cobrar(new SolicitudCobro(compradorId, TipoMovimiento.COMISION, gastos, torneoId, null,
                    "gastos-compra-" + pago.getId(), "Gastos administrativos de la compra en la rifa " + rifa.getTitulo()));
        }

        Ticket ticket = new Ticket();
        ticket.setCodigo(nuevoCodigo(rifaId));
        ticket.setRifa(rifa);
        ticket.setComprador(usuarioRepository.getReferenceById(compradorId));
        ticket.setMovimiento(pago);
        ticket.setMonto(monto);
        ticket.setComision(gastos);
        ticket.setMoneda(rifa.getMoneda());
        ticket.setClaveIdempotencia(claveIdempotencia);
        Ticket guardado = ticketRepository.save(ticket);
        bloqueados.forEach(n -> n.vender(guardado));
        numeroRepository.saveAllAndFlush(bloqueados);

        BigDecimal pozoRifa = pozoRifa(rifa);
        BigDecimal pozoTorneo = torneoId != null ? pozoTorneoService.calcularPozo(torneoId) : null;
        eventos.publishEvent(new NumerosVendidos(rifaId, torneoId, elegidos, compradorId, monto, pozoRifa, pozoTorneo));
        return resultado(guardado, elegidos, pozoRifa, pozoTorneo);
    }

    // --- reglas -------------------------------------------------------------------------------------

    private Rifa buscarPublica(Long rifaId) {
        return rifaRepository.findByIdAndEliminadoEnIsNull(rifaId)
                .filter(r -> r.getEstado() != EstadoRifa.BORRADOR)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.RIFA_NO_ENCONTRADA, "No existe la rifa " + rifaId));
    }

    /** La venta abierta ahora, y el que compra no es el organizador. */
    private Rifa buscarEnVenta(Long usuarioId, Long rifaId, Instant ahora) {
        Rifa rifa = buscarPublica(rifaId);
        if (rifa.getOrganizador().getId().equals(usuarioId)) {
            throw new ConflictoException(CodigoError.RIFA_PROPIA,
                    "El organizador no compra números de su propia rifa " + rifaId);
        }
        boolean abierta = rifa.getEstado() == EstadoRifa.EN_VENTA
                && !ahora.isBefore(rifa.getVentaDesde()) && ahora.isBefore(rifa.getVentaHasta());
        if (!abierta) {
            throw new ConflictoException(CodigoError.VENTA_CERRADA,
                    "La venta de la rifa " + rifaId + " no está abierta")
                    .con("ventaDesde", rifa.getVentaDesde())
                    .con("ventaHasta", rifa.getVentaHasta());
        }
        return rifa;
    }

    /** Sin repetidos y dentro del rango de la rifa; los devuelve en orden. */
    private static List<Integer> validarNumeros(Rifa rifa, List<Integer> numeros) {
        List<ErrorCampo> errores = new ArrayList<>();
        if (new HashSet<>(numeros).size() != numeros.size()) {
            errores.add(new ErrorCampo("numeros", ErrorCampo.Codigo.VALOR_INVALIDO, "Hay números repetidos"));
        }
        List<Integer> fuera = numeros.stream()
                .filter(n -> n < rifa.getNumeroDesde() || n > rifa.getNumeroHasta())
                .sorted()
                .toList();
        if (!fuera.isEmpty()) {
            errores.add(new ErrorCampo("numeros", ErrorCampo.Codigo.RANGO_INVALIDO,
                    "Fuera del rango " + rifa.getNumeroDesde() + " a " + rifa.getNumeroHasta() + ": " + fuera));
        }
        if (!errores.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", errores);
        }
        return numeros.stream().sorted().toList();
    }

    private static void validarClave(String clave) {
        if (clave == null || clave.isBlank() || clave.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo("Idempotency-Key",
                            clave == null || clave.isBlank() ? ErrorCampo.Codigo.REQUERIDO : ErrorCampo.Codigo.LONGITUD,
                            "Va una clave por compra, de 1 a 64 caracteres")));
        }
    }

    /** Cada número tiene que seguir reservado por el comprador y sin vencer. */
    private void validarReserva(List<Numero> bloqueados, List<Integer> elegidos, Long compradorId, Instant ahora) {
        Map<Integer, Numero> porNumero = bloqueados.stream()
                .collect(Collectors.toMap(n -> n.getId().getNumero(), Function.identity()));
        List<Integer> ocupados = new ArrayList<>();
        List<Integer> vencidos = new ArrayList<>();
        for (int numero : elegidos) {
            Numero fila = porNumero.get(numero);
            boolean suyo = fila != null && fila.getEstado() == EstadoNumero.RESERVADO
                    && fila.getReservadoPor().getId().equals(compradorId);
            if (fila == null || (suyo && fila.reservaVencida(ahora, rifaProperties.duracionReserva()))) {
                vencidos.add(numero);
            } else if (!suyo) {
                ocupados.add(numero);
            }
        }
        if (!ocupados.isEmpty()) {
            throw noDisponibles(ocupados);
        }
        if (!vencidos.isEmpty()) {
            throw new ConflictoException(CodigoError.RESERVA_VENCIDA,
                    "La reserva de " + vencidos + " venció: hay que volver a elegirlos")
                    .con("numeros", vencidos);
        }
    }

    /** La misma compra repetida devuelve su ticket; la clave usada para otra compra es un error del cliente. */
    private VentaRifaResultado repetida(Ticket ticket, Long compradorId, Long rifaId, List<Integer> numeros) {
        List<Integer> delTicket = numeroRepository.findByTicketIdOrderByIdNumero(ticket.getId()).stream()
                .map(n -> n.getId().getNumero())
                .toList();
        boolean misma = ticket.getComprador().getId().equals(compradorId) && ticket.getRifa().getId().equals(rifaId)
                && delTicket.equals(numeros.stream().sorted().toList());
        if (!misma) {
            throw new ConflictoException(CodigoError.CLAVE_IDEMPOTENCIA_USADA,
                    "La clave de idempotencia ya se usó para otra compra");
        }
        Rifa rifa = ticket.getRifa();
        Long torneoId = rifa.getTorneo() != null ? rifa.getTorneo().getId() : null;
        return resultado(ticket, delTicket, pozoRifa(rifa),
                torneoId != null ? pozoTorneoService.calcularPozo(torneoId) : null);
    }

    private static ApiException noDisponibles(List<Integer> numeros) {
        return new ConflictoException(CodigoError.NUMERO_NO_DISPONIBLE,
                "Los números " + numeros + " ya no están disponibles")
                .con("numeros", numeros);
    }

    // --- cuentas y armado ---------------------------------------------------------------------------

    /** Lo que va a los premios de lo vendido hasta ahora: sin el % que la rifa destina al torneo (HU-405). */
    private BigDecimal pozoRifa(Rifa rifa) {
        long vendidos = numeroRepository.countByIdRifaIdAndEstado(rifa.getId(), EstadoNumero.VENDIDO);
        return RepartoRifa.de(rifa, vendidos).aPremios();
    }

    private static VentaRifaResultado resultado(Ticket ticket, List<Integer> numeros, BigDecimal pozoRifa,
            BigDecimal pozoTorneo) {
        return new VentaRifaResultado(ticket.getId(), ticket.getCodigo(), numeros, ticket.getMonto(),
                ticket.getComision(), ticket.getMonto().add(ticket.getComision()), ticket.getCreadoEn(), pozoRifa,
                pozoTorneo);
    }

    /** Corto y legible para el comprobante, como R7-4F2K9Q; ticket.codigo es único. */
    private static String nuevoCodigo(Long rifaId) {
        StringBuilder codigo = new StringBuilder("R").append(rifaId).append('-');
        for (int i = 0; i < 6; i++) {
            codigo.append(LETRAS_CODIGO.charAt(AZAR.nextInt(LETRAS_CODIGO.length())));
        }
        return codigo.toString();
    }
}
