package com.hydra.pica.plataforma_pica.billetera.service;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cobro sin pasarela: aprueba siempre, salvo con la billetera bloqueada. La plata "viene de afuera",
 * así que no toca el saldo; el movimiento queda como registro del pago.
 */
@Service
@RequiredArgsConstructor
public class CobroSimulado implements CobroService {

    static final String PREFIJO_REFERENCIA = "sim-";

    private final BilleteraRepository billeteraRepository;
    private final MovimientoRepository movimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;

    @Override
    @Transactional
    public Movimiento cobrar(SolicitudCobro solicitud) {
        String referencia = PREFIJO_REFERENCIA + solicitud.claveIdempotencia();
        Billetera billetera = billeteraRepository.findByUsuarioId(solicitud.usuarioId())
                .orElseGet(() -> nuevaBilletera(solicitud.usuarioId()));

        var anterior = movimientoRepository.findByReferenciaExterna(referencia);
        if (anterior.isPresent()) {
            return mismoCobro(anterior.get(), billetera, solicitud);
        }
        if (billetera.getEstado() == EstadoBilletera.BLOQUEADA) {
            throw new CobroRechazadoException("La billetera del usuario " + solicitud.usuarioId() + " está bloqueada");
        }

        Movimiento movimiento = new Movimiento();
        movimiento.setBilletera(billetera);
        movimiento.setTipo(solicitud.tipo());
        movimiento.setMonto(solicitud.monto());
        movimiento.setMoneda(billetera.getMoneda());
        movimiento.setSaldoResultante(billetera.getSaldoDisponible());
        movimiento.setEstado(EstadoMovimiento.CONFIRMADO);
        movimiento.setReferenciaExterna(referencia);
        movimiento.setDescripcion(solicitud.descripcion());
        if (solicitud.torneoId() != null) {
            movimiento.setTorneo(torneoRepository.getReferenceById(solicitud.torneoId()));
        }
        if (solicitud.inscripcionId() != null) {
            movimiento.setInscripcion(inscripcionRepository.getReferenceById(solicitud.inscripcionId()));
        }
        return movimientoRepository.save(movimiento);
    }

    // el registro no crea billetera: aparece con el primer pago
    private Billetera nuevaBilletera(Long usuarioId) {
        Billetera billetera = new Billetera();
        billetera.setUsuario(usuarioRepository.getReferenceById(usuarioId));
        return billeteraRepository.save(billetera);
    }

    // la misma clave para otro pago es un error de quien cobra, no un reintento
    private Movimiento mismoCobro(Movimiento anterior, Billetera billetera, SolicitudCobro solicitud) {
        if (!anterior.getBilletera().getId().equals(billetera.getId()) || anterior.getTipo() != solicitud.tipo()
                || anterior.getMonto().compareTo(solicitud.monto()) != 0) {
            throw new IllegalArgumentException("La clave " + solicitud.claveIdempotencia() + " ya se usó en otro cobro");
        }
        return anterior;
    }
}
