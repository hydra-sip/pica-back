package com.hydra.pica.plataforma_pica.control.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.event.CuentaBancariaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.event.MovimientoBilleteraRegistrado;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoObjetado;
import com.hydra.pica.plataforma_pica.rifa.event.NumerosVendidos;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Cada listener AFTER_COMMIT y la pasada diaria corren en transacción nueva y aíslan fallos para no afectar el flujo
 * principal (HU-705, PICA-416).
 */
class DeteccionFraudeAutomaticaTest {

    private final DeteccionFraudeService servicio = mock(DeteccionFraudeService.class);
    private final PlatformTransactionManager transacciones = mock(PlatformTransactionManager.class);
    private final DeteccionFraudeAutomatica listener = new DeteccionFraudeAutomatica(servicio, transacciones, true);

    DeteccionFraudeAutomaticaTest() {
        when(transacciones.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void cadaEventoEvaluaSuReglaEnTransaccionNueva() {
        listener.alVenderNumeros(new NumerosVendidos(10L, 4L, List.of(1, 2), 3L, BigDecimal.TEN, BigDecimal.TEN, null));
        verify(servicio).evaluarCompraRifa(10L, 3L);

        listener.alRegistrarMovimiento(new MovimientoBilleteraRegistrado(8L, 3L, TipoMovimiento.CARGA, BigDecimal.TEN));
        verify(servicio).evaluarCarruselBilletera(3L);

        listener.alResolverCancelacion(new CancelacionResuelta(4L));
        verify(servicio).evaluarCancelacionesOrganizador(4L);

        listener.alObjetarResultado(new ResultadoObjetado(4L, 22L));
        verify(servicio).evaluarObjecionesUsuario(4L, 22L);

        listener.alRegistrarCuentaBancaria(new CuentaBancariaRegistrada(7L, 3L, TipoCuenta.CBU, "2850590940090418135201"));
        verify(servicio).evaluarCuentaCompartida(3L, "2850590940090418135201");

        verify(transacciones, org.mockito.Mockito.times(5)).getTransaction(org.mockito.ArgumentMatchers.argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void pasadaDiariaRespetaToggleYAislaErrores() {
        DeteccionFraudeAutomatica desactivada = new DeteccionFraudeAutomatica(servicio, transacciones, false);
        desactivada.pasadaDiaria();
        verifyNoInteractions(servicio);

        doThrow(new IllegalStateException("fallo simulado")).when(servicio).evaluarTodo();
        listener.pasadaDiaria();
        verify(servicio).evaluarTodo();
    }
}
