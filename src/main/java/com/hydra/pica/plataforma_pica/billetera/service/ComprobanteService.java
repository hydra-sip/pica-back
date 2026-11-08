package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.ComprobantePago;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** El comprobante del pago de una inscripción (HU-603): sale de sus movimientos, no se guarda aparte. */
@Service
@RequiredArgsConstructor
public class ComprobanteService {

    private final InscripcionRepository inscripcionRepository;
    private final MovimientoRepository movimientoRepository;

    /**
     * Solo para quien se inscribió (el capitán, en una de equipo); de otro, 404 INSCRIPCION_NO_ENCONTRADA. Sin pago
     * (pendiente o de un torneo gratis), 404 COMPROBANTE_NO_ENCONTRADO.
     */
    @Transactional(readOnly = true)
    public ComprobantePago deInscripcion(Long usuarioId, Long inscripcionId) {
        inscripcionRepository.torneoIdDeInscripcionPropia(inscripcionId, usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.INSCRIPCION_NO_ENCONTRADA,
                        "No existe la inscripción " + inscripcionId));
        List<Movimiento> movimientos = movimientoRepository.pagosDeLaInscripcion(inscripcionId);
        Movimiento pago = movimientos.stream().filter(m -> m.getTipo() == TipoMovimiento.INSCRIPCION).findFirst()
                .orElseThrow(() -> new NoEncontradoException(CodigoError.COMPROBANTE_NO_ENCONTRADO,
                        "La inscripción " + inscripcionId + " no tiene un pago"));
        BigDecimal gastos = movimientos.stream().filter(m -> m.getTipo() == TipoMovimiento.COMISION)
                .map(Movimiento::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add);

        Inscripcion inscripcion = inscripcionRepository.findById(inscripcionId).orElseThrow();
        Usuario pagador = pago.getBilletera().getUsuario();
        String equipo = inscripcion.getEquipo() != null ? inscripcion.getEquipo().getNombre() : null;
        String torneo = inscripcion.getTorneo().getNombre();
        return new ComprobantePago(
                "I" + inscripcion.getTorneo().getId() + "-" + pago.getId(),
                pago.getCreadoEn(),
                pagador.getPersona().getNombres() + " " + pagador.getPersona().getApellidos(),
                pagador.getUsername(),
                equipo != null ? "Inscripción de " + equipo + " al torneo " + torneo : "Inscripción al torneo " + torneo,
                inscripcion.getTorneo().getId(),
                torneo,
                equipo,
                pago.getMonto(),
                gastos,
                pago.getMonto().add(gastos),
                pago.getMoneda(),
                pago.getCustodia() != null ? pago.getCustodia().getEstado() : null);
    }
}
