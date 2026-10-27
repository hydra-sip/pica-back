package com.hydra.pica.plataforma_pica.rifa.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.event.NumerosVendidos;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.service.PozoTorneoService;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VentaRifaServiceTest {

    @Mock private RifaRepository rifaRepository;
    @Mock private NumeroRepository numeroRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private CobroService cobroService;
    @Mock private PozoTorneoService pozoTorneoService;
    @Mock private ApplicationEventPublisher eventos;

    @InjectMocks
    private VentaRifaService ventaRifaService;

    private Rifa rifa;
    private Usuario comprador;
    private Torneo torneo;

    @BeforeEach
    void setUp() {
        rifa = new Rifa();
        rifa.setTitulo("Rifa Oficial");
        rifa.setEstado(EstadoRifa.EN_VENTA);
        rifa.setNumeroDesde(1);
        rifa.setNumeroHasta(100);
        rifa.setPrecioNumero(new BigDecimal("250.00"));
        rifa.setMoneda("ARS");

        torneo = new Torneo();
        torneo.setNombre("Torneo Valorant");
        org.springframework.test.util.ReflectionTestUtils.setField(torneo, "id", 1L);
        rifa.setTorneo(torneo);
        org.springframework.test.util.ReflectionTestUtils.setField(rifa, "id", 1L);

        comprador = new Usuario();
        org.springframework.test.util.ReflectionTestUtils.setField(comprador, "id", 10L);
    }

    @Test
    @DisplayName("Rechaza venta si la rifa no está EN_VENTA")
    void rifaNoEnVentaRechaza() {
        rifa.setEstado(EstadoRifa.BORRADOR);
        when(rifaRepository.findById(1L)).thenReturn(Optional.of(rifa));

        assertThatThrownBy(() -> ventaRifaService.vender(10L, 1L, List.of(5), "clave-1"))
                .isInstanceOf(ConflictoException.class)
                .hasMessageContaining("EN_VENTA");
    }

    @Test
    @DisplayName("Rechaza venta si hay números duplicados en la petición")
    void numerosDuplicadosRechaza() {
        when(rifaRepository.findById(1L)).thenReturn(Optional.of(rifa));

        assertThatThrownBy(() -> ventaRifaService.vender(10L, 1L, List.of(5, 5), "clave-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("duplicados");
    }

    @Test
    @DisplayName("Rechaza venta si el número está fuera de rango")
    void numeroFueraDeRangoRechaza() {
        when(rifaRepository.findById(1L)).thenReturn(Optional.of(rifa));

        assertThatThrownBy(() -> ventaRifaService.vender(10L, 1L, List.of(150), "clave-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("rango");
    }

    @Test
    @DisplayName("Rechaza venta si el número ya fue vendido")
    void numeroYaVendidoRechaza() {
        when(rifaRepository.findById(1L)).thenReturn(Optional.of(rifa));

        Numero vendido = Numero.vendido(rifa, 7, new Ticket());
        when(numeroRepository.findByIdRifaIdAndIdNumeroIn(1L, List.of(7)))
                .thenReturn(List.of(vendido));

        assertThatThrownBy(() -> ventaRifaService.vender(10L, 1L, List.of(7), "clave-1"))
                .isInstanceOf(ConflictoException.class)
                .satisfies(e -> {
                    ConflictoException ce = (ConflictoException) e;
                    assertThat(ce.getCodigo()).isEqualTo(CodigoError.NUMERO_YA_VENDIDO);
                });
    }

    @Test
    @DisplayName("Venta exitosa cobra, guarda ticket, números y publica evento para WebSocket")
    void ventaExitosaCobraYPublica() {
        when(rifaRepository.findById(1L)).thenReturn(Optional.of(rifa));
        when(numeroRepository.findByIdRifaIdAndIdNumeroIn(1L, List.of(7, 8))).thenReturn(List.of());
        when(usuarioRepository.findById(10L)).thenReturn(Optional.of(comprador));

        Movimiento pago = new Movimiento();
        when(cobroService.cobrar(any(SolicitudCobro.class))).thenReturn(pago);

        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(numeroRepository.countByIdRifaIdAndEstado(1L, EstadoNumero.VENDIDO)).thenReturn(2L);
        when(pozoTorneoService.calcularPozo(any(Long.class))).thenReturn(new BigDecimal("80500.00"));

        VentaRifaResultado resultado = ventaRifaService.vender(10L, 1L, List.of(7, 8), "clave-1");

        assertThat(resultado.numeros()).containsExactly(7, 8);
        assertThat(resultado.montoTotal()).isEqualByComparingTo("500.00");
        assertThat(resultado.pozoRifa()).isEqualByComparingTo("500.00");
        assertThat(resultado.pozoTorneo()).isEqualByComparingTo("80500.00");

        ArgumentCaptor<NumerosVendidos> captor = ArgumentCaptor.forClass(NumerosVendidos.class);
        verify(eventos).publishEvent(captor.capture());

        NumerosVendidos evento = captor.getValue();
        assertThat(evento.rifaId()).isEqualTo(1L);
        assertThat(evento.numeros()).containsExactly(7, 8);
        assertThat(evento.montoTotal()).isEqualByComparingTo("500.00");
        assertThat(evento.pozoRifa()).isEqualByComparingTo("500.00");
        assertThat(evento.pozoTorneo()).isEqualByComparingTo("80500.00");
    }
}
