package com.hydra.pica.plataforma_pica.inscripcion.service;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionDetalle;
import com.hydra.pica.plataforma_pica.inscripcion.dto.ResumenPago;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.GastosAdministrativos;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** El pago de la inscripción (PICA-209, HU-603): el resumen de lo que se paga y el cobro que la confirma. */
@Service
@RequiredArgsConstructor
public class PagoInscripcionService {

    private final InscripcionRepository inscripcionRepository;
    private final UsuarioRepository usuarioRepository;
    private final CobroService cobroService;
    private final InscripcionComun comun;

    /** Lo que va a pagar el participante o el capitán; solo se ve mientras la inscripción está pendiente de pago. */
    @Transactional(readOnly = true)
    public ResumenPago resumenPago(Long usuarioId, Long id) {
        Inscripcion inscripcion = comun.buscar(id);
        if (!comun.esDe(inscripcion, usuarioId)) {
            throw comun.noEncontrada(id);
        }
        if (inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": el resumen es de una PENDIENTE_PAGO");
        }
        return ResumenPago.de(inscripcion, comun.confirmadas(inscripcion.getTorneo().getId()));
    }

    /**
     * Cobra el valor de la inscripción por la interfaz de cobro y la deja confirmada. Primero bloquea la
     * fila del torneo y recién después lee la inscripción, así un pago simultáneo de la misma inscripción
     * o por el último lugar ve lo que dejó el anterior.
     *
     * <p>El bloqueo del torneo dura todo el cobro. El cobro con saldo (HU-603) es en la misma base y no se nota;
     * después del torneo bloquea la billetera, siempre en ese orden.
     */
    @Transactional
    public InscripcionDetalle pagar(Long usuarioId, Long id) {
        Long torneoId = inscripcionRepository.torneoIdDeInscripcionPropia(id, usuarioId)
                .orElseThrow(() -> comun.noEncontrada(id));
        Torneo torneo = comun.torneoBloqueado(torneoId);
        Inscripcion inscripcion = comun.buscar(id);
        if (inscripcion.getEstado() == EstadoInscripcion.CONFIRMADA) {
            return comun.detalleDe(inscripcion, usuarioId);
        }
        // antes que el estado: el cierre cancela las pendientes, y a quien llega tarde le sirve saber que cerró
        comun.validarTorneoAbierto(torneo);
        if (inscripcion.getEstado() != EstadoInscripcion.PENDIENTE_PAGO) {
            throw new ConflictoException(CodigoError.INSCRIPCION_NO_EDITABLE,
                    "La inscripción " + id + " está " + inscripcion.getEstado() + ": solo se paga en PENDIENTE_PAGO");
        }
        comun.validarUsuario(usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.USUARIO_NO_ENCONTRADO, "No existe el usuario " + usuarioId)));
        comun.validarOrganizadorNoBloqueado(torneo);
        comun.validarCupo(torneo);
        BigDecimal gastos = GastosAdministrativos.monto(torneo.getValorInscripcion(), torneo.getPctGastosAdministrativos());
        BigDecimal total = torneo.getValorInscripcion().add(gastos);
        // HU-603: si el saldo no alcanza para todo, falla antes de cobrar y con el faltante del total
        if (total.signum() > 0) {
            cobroService.exigirSaldo(usuarioId, total);
        }
        // un torneo sin costo no pasa por el cobro: V9 exige monto > 0
        if (torneo.getValorInscripcion().signum() > 0) {
            cobroService.cobrar(new SolicitudCobro(usuarioId, TipoMovimiento.INSCRIPCION, torneo.getValorInscripcion(),
                    torneoId, id, "inscripcion-" + id, "Inscripción al torneo " + torneo.getNombre()));
        }
        // los gastos administrativos van aparte, en otro movimiento: la inscripción es del torneo y esto de la
        // plataforma (PICA-283)
        if (gastos.signum() > 0) {
            cobroService.cobrar(new SolicitudCobro(usuarioId, TipoMovimiento.COMISION, gastos, torneoId, id,
                    "gastos-inscripcion-" + id, "Gastos administrativos de la inscripción al torneo " + torneo.getNombre()));
        }
        inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
        Inscripcion pagada = inscripcionRepository.saveAndFlush(inscripcion);
        comun.cerrarSiSeCompletoElCupo(torneo);
        return comun.detalleDe(pagada, usuarioId);
    }
}
