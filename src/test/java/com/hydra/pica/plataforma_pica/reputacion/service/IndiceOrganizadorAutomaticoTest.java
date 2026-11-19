package com.hydra.pica.plataforma_pica.reputacion.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.event.CancelacionResuelta;
import com.hydra.pica.plataforma_pica.torneo.event.TorneoEstadoCambiado;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * La cancelación mueve el índice del organizador en su propia transacción y no rompe la operación ya confirmada si
 * falla.
 */
class IndiceOrganizadorAutomaticoTest {

    private final PerfilOrganizadorService servicio = mock(PerfilOrganizadorService.class);
    private final PlatformTransactionManager transacciones = mock(PlatformTransactionManager.class);
    private final IndiceOrganizadorAutomatico automatico = new IndiceOrganizadorAutomatico(servicio, transacciones);

    IndiceOrganizadorAutomaticoTest() {
        when(transacciones.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void alCancelarTorneoRegistraEnNuevaTransaccion() {
        automatico.alCambiarEstadoTorneo(
                new TorneoEstadoCambiado(12L, EstadoTorneo.EN_CURSO, EstadoTorneo.CANCELADO));

        verify(servicio).registrarCancelacion(12L);
        verify(transacciones).getTransaction(org.mockito.ArgumentMatchers.argThat(
                (TransactionDefinition d) -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void otrosEstadosNoPenalizanAlOrganizador() {
        automatico.alCambiarEstadoTorneo(
                new TorneoEstadoCambiado(12L, EstadoTorneo.EN_CURSO, EstadoTorneo.FINALIZADO));

        verifyNoInteractions(servicio);
    }

    @Test
    void alResolverCancelacionRegistraYSiFallaNoPropaga() {
        doThrow(new IllegalStateException("caída")).when(servicio).registrarCancelacion(19L);

        automatico.alResolverCancelacion(new CancelacionResuelta(19L));

        verify(servicio).registrarCancelacion(19L);
    }
}
