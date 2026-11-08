package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cobro con el saldo de la billetera (HU-603): lo pagado sale del disponible y pasa al retenido del que paga, en
 * custodia hasta que termine el evento. La billetera se bloquea (FOR UPDATE) durante el cobro, así dos pagos
 * simultáneos nunca la dejan en negativo (RNF-CON-01).
 */
@Service
@ConditionalOnProperty(name = "app.cobro.modo", havingValue = "saldo", matchIfMissing = true)
@RequiredArgsConstructor
public class CobroConSaldo implements CobroService {

    /** Distingue los pagos con saldo de los simulados ("sim-"), que no tocaron la billetera. */
    static final String PREFIJO_REFERENCIA = "pago-";

    private final BilleteraRepository billeteraRepository;
    private final MovimientoRepository movimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;

    @Override
    @Transactional
    public Movimiento cobrar(SolicitudCobro solicitud) {
        String referencia = PREFIJO_REFERENCIA + solicitud.claveIdempotencia();
        // primero el bloqueo: un reintento simultáneo con la misma clave espera y encuentra el movimiento del otro
        Billetera billetera = bloqueada(solicitud.usuarioId());

        var anterior = movimientoRepository.findByReferenciaExterna(referencia);
        if (anterior.isPresent()) {
            return mismoCobro(anterior.get(), billetera, solicitud);
        }
        exigirDisponible(billetera, solicitud.monto());

        billetera.setSaldoDisponible(billetera.getSaldoDisponible().subtract(solicitud.monto()));
        billetera.setSaldoRetenido(billetera.getSaldoRetenido().add(solicitud.monto()));
        billeteraRepository.save(billetera);

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

    @Override
    @Transactional
    public void exigirSaldo(Long usuarioId, BigDecimal total) {
        exigirDisponible(bloqueada(usuarioId), total);
    }

    private static void exigirDisponible(Billetera billetera, BigDecimal monto) {
        if (billetera.getEstado() == EstadoBilletera.BLOQUEADA) {
            throw new CobroRechazadoException("La billetera " + billetera.getId() + " está bloqueada");
        }
        BigDecimal faltante = monto.subtract(billetera.getSaldoDisponible());
        if (faltante.signum() > 0) {
            throw new SaldoInsuficienteException(faltante.setScale(2));
        }
    }

    // el bloqueo dura hasta que termina la transacción de quien cobra
    private Billetera bloqueada(Long usuarioId) {
        return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElseGet(() -> {
            // respaldo por si algún test persiste un Usuario directo sin pasar por UsuarioService.crear
            Billetera nueva = new Billetera();
            nueva.setUsuario(usuarioRepository.getReferenceById(usuarioId));
            billeteraRepository.saveAndFlush(nueva);
            return billeteraRepository.findConBloqueoPorUsuarioId(usuarioId).orElseThrow();
        });
    }

    // la misma clave para otro pago es un error de quien cobra, no un reintento
    private static Movimiento mismoCobro(Movimiento anterior, Billetera billetera, SolicitudCobro solicitud) {
        if (!anterior.getBilletera().getId().equals(billetera.getId()) || anterior.getTipo() != solicitud.tipo()
                || anterior.getMonto().compareTo(solicitud.monto()) != 0) {
            throw new IllegalArgumentException("La clave " + solicitud.claveIdempotencia() + " ya se usó en otro cobro");
        }
        return anterior;
    }
}
