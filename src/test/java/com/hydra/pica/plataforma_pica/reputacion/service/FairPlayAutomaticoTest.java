package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import com.hydra.pica.plataforma_pica.partido.event.AusenciaDeclarada;
import com.hydra.pica.plataforma_pica.partido.event.CheckinHecho;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.domain.TipoEventoFairPlay;
import com.hydra.pica.plataforma_pica.reputacion.event.SancionAplicada;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Cada evento mueve el índice en una transacción nueva, y un error no se propaga a lo que ya se confirmó. */
class FairPlayAutomaticoTest {

    private final FairPlayService servicio = mock(FairPlayService.class);
    private final PlatformTransactionManager transacciones = mock(PlatformTransactionManager.class);
    private final FairPlayAutomatico automatico = new FairPlayAutomatico(servicio, transacciones);

    FairPlayAutomaticoTest() {
        when(transacciones.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void unCheckinSumaAQuienRespondePorLaInscripcion() {
        automatico.alHacerCheckin(new CheckinHecho(9L, 4L, 31L));

        verify(servicio).registrarDeInscripcion(31L, TipoEventoFairPlay.CHECKIN, 9L, 4L);
        verify(transacciones).getTransaction(org.mockito.ArgumentMatchers.argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void unaAusenciaRestaACadaAusenteAunqueUnoFalle() {
        doThrow(new IllegalStateException("no está")).when(servicio)
                .registrarDeInscripcion(31L, TipoEventoFairPlay.AUSENCIA, 9L, 4L);

        automatico.alDeclararAusencia(new AusenciaDeclarada(9L, 4L, List.of(31L, 32L)));

        verify(servicio).registrarDeInscripcion(32L, TipoEventoFairPlay.AUSENCIA, 9L, 4L);
    }

    @Test
    void unaSancionRestaAlSancionadoYUnErrorQuedaEnElLog() {
        doThrow(new IllegalStateException("caída")).when(servicio).registrarSancion(3L, 9L, 7L, Gravedad.GRAVE);

        automatico.alSancionar(new SancionAplicada(7L, 9L, 3L, Gravedad.GRAVE));

        verify(servicio).registrarSancion(3L, 9L, 7L, Gravedad.GRAVE);
    }
}
