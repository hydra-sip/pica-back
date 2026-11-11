package com.hydra.pica.plataforma_pica.torneo.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.Custodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.CustodiaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Gasto;
import com.hydra.pica.plataforma_pica.torneo.domain.GastoId;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.HonorarioDesignacion;
import com.hydra.pica.plataforma_pica.torneo.dto.PagoGastoRequest;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.GastoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * El pago de los gastos previstos de un torneo (HU-604). Un honorario arbitral solo es deducible si se paga por la
 * plataforma: el organizador lo deposita desde su billetera en la custodia del torneo para un árbitro designado con la
 * identidad verificada, y la liquidación (HU-606) se lo acredita al árbitro y le devuelve el gasto al organizador
 * (wireframe 49). Un gasto común se registra con su comprobante.
 * <p>
 * Si el árbitro rechaza la designación, lo quitan o el organizador retira el depósito antes de que termine el torneo,
 * el depósito queda REVERTIDO y vuelve al disponible del organizador con un REINTEGRO. Si el torneo se cancela, lo
 * devuelve la cancelación (HU-605) como cualquier pago de la custodia.
 */
@Service
@RequiredArgsConstructor
public class HonorariosService {

    static final String IDENTIDAD_SIN_VERIFICAR =
            "El usuario propuesto debe verificar su identidad antes de arbitrar torneos con dinero";
    static final String NO_DEDUCIBLE =
            "Los honorarios arbitrales solo son deducibles si se abonan mediante transferencia interna de la plataforma";

    private static final Set<EstadoTorneo> TERMINADOS = Set.of(EstadoTorneo.FINALIZADO, EstadoTorneo.CANCELADO);
    private static final Set<EstadoDesignacion> VIGENTES = Set.of(EstadoDesignacion.PENDIENTE,
            EstadoDesignacion.ACEPTADO);
    private static final String ENTIDAD = "CUSTODIA";

    private final TorneosPropios torneosPropios;
    private final GastoRepository gastoRepository;
    private final ArbitroDesignadoRepository arbitroRepository;
    private final CobroService cobroService;
    private final BilleteraRepository billeteraRepository;
    private final MovimientoRepository movimientoRepository;
    private final CustodiaRepository custodiaRepository;
    private final AuditoriaService auditoriaService;

    /** Un torneo con dinero cobra inscripción: para arbitrarlo hace falta la identidad verificada. */
    static boolean conDinero(Torneo torneo) {
        return torneo.getValorInscripcion() != null && torneo.getValorInscripcion().signum() > 0;
    }

    /** 409 IDENTIDAD_NO_VERIFICADA si el torneo tiene dinero y el usuario no verificó su identidad. */
    static void exigirIdentidad(Torneo torneo, Usuario usuario) {
        if (conDinero(torneo)) {
            exigirIdentidad(usuario);
        }
    }

    private static void exigirIdentidad(Usuario usuario) {
        if (!usuario.identidadVerificada()) {
            throw new ConflictoException(CodigoError.IDENTIDAD_NO_VERIFICADA, IDENTIDAD_SIN_VERIFICAR)
                    .con("usuarioId", usuario.getId());
        }
    }

    // --- el organizador paga ------------------------------------------------------------------------

    /**
     * Con {@code arbitroId}, deposita el honorario en la custodia del torneo; con {@code comprobante}, registra un
     * gasto común pagado por fuera. El torneo, propio y bloqueado, sin terminar.
     */
    @Transactional
    public GastoDetalle pagar(Long organizadorId, Long torneoId, int gastoId, PagoGastoRequest request) {
        boolean conComprobante = request.comprobante() != null && !request.comprobante().isBlank();
        if ((request.arbitroId() != null) == conComprobante) {
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo("arbitroId", ErrorCampo.Codigo.VALOR_INVALIDO,
                            "Va el árbitro (un honorario por la plataforma) o el comprobante (un gasto pagado por "
                                    + "fuera), uno de los dos")));
        }
        Torneo torneo = sinTerminar(organizadorId, torneoId);
        Gasto gasto = gasto(torneoId, gastoId);
        return request.arbitroId() != null
                ? depositar(torneo, gasto, request.arbitroId())
                : registrarComprobante(gasto, request.comprobante().strip());
    }

    /** Devuelve al organizador el honorario depositado. 409 HONORARIO_SIN_DEPOSITAR si no hay depósito. */
    @Transactional
    public GastoDetalle retirar(Long organizadorId, Long torneoId, int gastoId) {
        Torneo torneo = sinTerminar(organizadorId, torneoId);
        Gasto gasto = gasto(torneoId, gastoId);
        if (gasto.getMovimiento() == null) {
            throw new ConflictoException(CodigoError.HONORARIO_SIN_DEPOSITAR,
                    "El gasto " + gastoId + " no tiene un honorario depositado");
        }
        devolver(torneo, gasto, organizadorId, "lo retiró el organizador");
        return GastoDetalle.de(gasto);
    }

    private GastoDetalle depositar(Torneo torneo, Gasto gasto, Long arbitroId) {
        int gastoId = gasto.getId().getGastoId();
        if (!gasto.esHonorario()) {
            throw new ConflictoException(CodigoError.GASTO_NO_ES_HONORARIO, "El gasto " + gastoId
                    + " no es un honorario arbitral: un gasto común se registra con su comprobante");
        }
        if (gasto.getMovimiento() != null) {
            throw new ConflictoException(CodigoError.HONORARIO_YA_DEPOSITADO,
                    "El honorario " + gastoId + " ya está depositado: para cambiar de árbitro, primero se retira")
                    .con("arbitroId", gasto.getBeneficiario().getId());
        }
        ArbitroDesignado designacion = arbitroRepository.findById(new ArbitroDesignadoId(torneo.getId(), arbitroId))
                .filter(d -> VIGENTES.contains(d.getEstado()))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.ARBITRO_NO_ENCONTRADO,
                        "El usuario " + arbitroId + " no está designado en el torneo " + torneo.getId()));
        Usuario arbitro = designacion.getUsuario();
        exigirIdentidad(arbitro);

        // una clave por depósito: si se retiró, el siguiente es otro pago
        String base = "honorario-t" + torneo.getId() + "-g" + gastoId + "-";
        long anteriores = movimientoRepository.countByReferenciaExternaStartingWith("pago-" + base)
                + movimientoRepository.countByReferenciaExternaStartingWith("sim-" + base);
        Movimiento deposito = cobroService.cobrar(new SolicitudCobro(torneo.getOrganizador().getId(),
                TipoMovimiento.PAGO_SERVICIO, gasto.getMonto(), torneo.getId(), null, base + (anteriores + 1),
                corta("Honorario arbitral de " + nombre(arbitro) + " en " + torneo.getNombre()
                        + ": en custodia hasta la liquidación")));

        gasto.setBeneficiario(arbitro);
        gasto.setMovimiento(deposito);
        gasto.setMontoAbonado(gasto.getMonto());
        gasto.setFechaAbonado(Instant.now());
        gasto.setComprobante("Transferencia interna " + deposito.getReferenciaExterna());
        return GastoDetalle.de(gastoRepository.saveAndFlush(gasto));
    }

    private GastoDetalle registrarComprobante(Gasto gasto, String comprobante) {
        int gastoId = gasto.getId().getGastoId();
        if (gasto.esHonorario()) {
            throw new ConflictoException(CodigoError.HONORARIO_NO_DEDUCIBLE, NO_DEDUCIBLE).con("gastoId", gastoId);
        }
        if (gasto.getMontoAbonado().signum() > 0) {
            throw new ConflictoException(CodigoError.GASTO_YA_PAGADO, "El gasto " + gastoId + " ya está pagado");
        }
        gasto.setMontoAbonado(gasto.getMonto());
        gasto.setFechaAbonado(Instant.now());
        gasto.setComprobante(comprobante);
        return GastoDetalle.de(gastoRepository.saveAndFlush(gasto));
    }

    // --- el árbitro sale ----------------------------------------------------------------------------

    /**
     * El árbitro rechazó la designación o lo quitaron: vuelve al organizador lo que estaba depositado para él. Corre en
     * la transacción de quien llama, con el torneo ya bloqueado.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void devolverDelArbitro(Torneo torneo, Long arbitroId, String motivo) {
        for (Gasto gasto : gastoRepository.depositadosDelArbitro(torneo.getId(), arbitroId)) {
            devolver(torneo, gasto, null, motivo);
        }
    }

    /**
     * El depósito queda REVERTIDO (así ya no cuenta en la custodia, ni para la liquidación ni para la cancelación) y
     * vuelve del retenido al disponible del organizador con un REINTEGRO. La custodia se bloquea como para liberarla:
     * si ya se liberó, 409 CUSTODIA_LIBERADA.
     */
    private void devolver(Torneo torneo, Gasto gasto, Long usuarioId, String motivo) {
        Movimiento deposito = gasto.getMovimiento();
        Usuario arbitro = gasto.getBeneficiario();
        Custodia custodia = deposito.getCustodia();
        if (custodia != null) {
            EstadoCustodia estado = custodiaRepository.findParaLiberarPorTorneoId(torneo.getId()).orElseThrow()
                    .getEstado();
            if (estado != EstadoCustodia.RETENIDA) {
                throw new ConflictoException(CodigoError.CUSTODIA_LIBERADA,
                        "La custodia del torneo " + torneo.getId() + " ya está " + estado
                                + ": el honorario ya no se devuelve").con("estado", estado);
            }
        }
        deposito.setEstado(EstadoMovimiento.REVERTIDO);
        movimientoRepository.save(deposito);

        Movimiento reintegro = null;
        // un depósito simulado no tocó la billetera
        if (deposito.getReferenciaExterna().startsWith("pago-")) {
            Billetera billetera = billeteraRepository.findConBloqueoPorUsuarioId(torneo.getOrganizador().getId())
                    .orElseThrow();
            billetera.setSaldoRetenido(billetera.getSaldoRetenido().subtract(deposito.getMonto()));
            billetera.setSaldoDisponible(billetera.getSaldoDisponible().add(deposito.getMonto()));
            billeteraRepository.save(billetera);

            reintegro = new Movimiento();
            reintegro.setBilletera(billetera);
            reintegro.setTorneo(torneo);
            reintegro.setTipo(TipoMovimiento.REINTEGRO);
            reintegro.setMonto(deposito.getMonto());
            reintegro.setMoneda(billetera.getMoneda());
            reintegro.setSaldoResultante(billetera.getSaldoDisponible());
            reintegro.setEstado(EstadoMovimiento.CONFIRMADO);
            reintegro.setReferenciaExterna("reint-" + deposito.getReferenciaExterna());
            reintegro.setDescripcion(corta("Devolución del honorario arbitral de " + nombre(arbitro) + " en "
                    + torneo.getNombre() + ": " + motivo));
            reintegro = movimientoRepository.save(reintegro);
        }

        gasto.setMovimiento(null);
        gasto.setBeneficiario(null);
        gasto.setMontoAbonado(BigDecimal.ZERO);
        gasto.setFechaAbonado(null);
        gasto.setComprobante(null);
        gastoRepository.saveAndFlush(gasto);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", "DEVOLUCION_HONORARIO");
        detalle.put("torneoId", torneo.getId());
        detalle.put("gastoId", gasto.getId().getGastoId());
        detalle.put("arbitroId", arbitro.getId());
        detalle.put("monto", deposito.getMonto());
        detalle.put("depositoId", deposito.getId());
        if (reintegro != null) {
            detalle.put("reintegroId", reintegro.getId());
        }
        detalle.put("motivo", motivo);
        auditoriaService.registrar(usuarioId, AccionAuditoria.PAGO, custodia != null ? ENTIDAD : "TORNEO",
                (custodia != null ? custodia.getId() : torneo.getId()).toString(), detalle);
    }

    // --- el árbitro ve ------------------------------------------------------------------------------

    /** Lo depositado para el árbitro en cada torneo, para Mis designaciones (wireframes 48 y 49). */
    @Transactional(readOnly = true)
    public Map<Long, HonorarioDesignacion> delArbitro(Long usuarioId) {
        Map<Long, HonorarioDesignacion> porTorneo = new LinkedHashMap<>();
        for (Gasto gasto : gastoRepository.honorariosDelArbitro(usuarioId)) {
            porTorneo.merge(gasto.getId().getTorneoId(),
                    new HonorarioDesignacion(gasto.getMovimiento().getMonto(), gasto.getMovimiento().getMoneda(),
                            GastoDetalle.estadoPago(gasto)),
                    (a, b) -> new HonorarioDesignacion(a.monto().add(b.monto()), a.moneda(), a.estado()));
        }
        return porTorneo;
    }

    // --- auxiliares ---------------------------------------------------------------------------------

    private Torneo sinTerminar(Long organizadorId, Long torneoId) {
        Torneo torneo = torneosPropios.buscarBloqueado(organizadorId, torneoId);
        if (TERMINADOS.contains(torneo.getEstado())) {
            throw new ConflictoException(CodigoError.TORNEO_NO_EDITABLE,
                    "El torneo " + torneoId + " está " + torneo.getEstado() + ": sus gastos ya no se pagan");
        }
        return torneo;
    }

    private Gasto gasto(Long torneoId, int gastoId) {
        return gastoRepository.findById(new GastoId(torneoId, gastoId))
                .orElseThrow(() -> new NoEncontradoException(CodigoError.GASTO_NO_ENCONTRADO,
                        "El torneo " + torneoId + " no tiene el gasto " + gastoId));
    }

    private static String nombre(Usuario usuario) {
        return usuario.getPersona().getNombres() + " " + usuario.getPersona().getApellidos();
    }

    private static String corta(String texto) {
        return texto.length() > 255 ? texto.substring(0, 255) : texto;
    }
}
