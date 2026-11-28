package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hydra.pica.plataforma_pica.billetera.event.TorneoLiquidado;
import com.hydra.pica.plataforma_pica.partido.event.CheckinHecho;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.rifa.event.RifaSorteada;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Cada evento evalúa insignias en una transacción nueva, y un error no se propaga a la operación confirmada. */
class InsigniaAutomaticaTest {

    private final InsigniaService servicio = mock(InsigniaService.class);
    private final PlatformTransactionManager transacciones = mock(PlatformTransactionManager.class);
    private final InsigniaAutomatica automatico = new InsigniaAutomatica(servicio, transacciones);

    InsigniaAutomaticaTest() {
        when(transacciones.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void alConfirmarResultadoEvaluaEnTransaccionNueva() {
        automatico.alConfirmarResultado(new ResultadoConfirmado(10L, 25L, 7L, true));

        verify(servicio).evaluarResultadoConfirmado(10L, 25L, true);
        verify(transacciones).getTransaction(argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void alLiquidarTorneoEvaluaTorneoFinalizadoEnTransaccionNueva() {
        automatico.alLiquidarTorneo(new TorneoLiquidado(12L, 3L));

        verify(servicio).evaluarTorneoFinalizado(12L);
        verify(transacciones).getTransaction(argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void alSortearRifaEvaluaRifaEnTransaccionNueva() {
        automatico.alSortearRifa(new RifaSorteada(44L, null));

        verify(servicio).evaluarRifaSorteada(44L);
        verify(transacciones).getTransaction(argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void alHacerCheckinEvaluaLaInscripcionEnTransaccionNueva() {
        automatico.alHacerCheckin(new CheckinHecho(9L, 4L, 31L));

        verify(servicio).evaluarCheckin(31L);
        verify(transacciones).getTransaction(argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void siFallaLaEvaluacionDeCualquierEventoElErrorNoSePropaga() {
        doThrow(new IllegalStateException("falla simulada")).when(servicio)
                .evaluarResultadoConfirmado(1L, 2L, false);
        doThrow(new IllegalStateException("falla simulada")).when(servicio)
                .evaluarTorneoFinalizado(1L);
        doThrow(new IllegalStateException("falla simulada")).when(servicio)
                .evaluarRifaSorteada(8L);
        doThrow(new IllegalStateException("falla simulada")).when(servicio)
                .evaluarCheckin(9L);

        automatico.alConfirmarResultado(new ResultadoConfirmado(1L, 2L, 3L, false));
        automatico.alLiquidarTorneo(new TorneoLiquidado(1L, 4L));
        automatico.alSortearRifa(new RifaSorteada(8L, null));
        automatico.alHacerCheckin(new CheckinHecho(1L, 2L, 9L));

        verify(servicio).evaluarResultadoConfirmado(1L, 2L, false);
        verify(servicio).evaluarTorneoFinalizado(1L);
        verify(servicio).evaluarRifaSorteada(8L);
        verify(servicio).evaluarCheckin(9L);
    }
}
