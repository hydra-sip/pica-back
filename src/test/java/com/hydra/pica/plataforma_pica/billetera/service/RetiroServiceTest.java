package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoBilletera;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.common.error.ProhibidoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma;
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
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class RetiroServiceTest {

    private static final Long USUARIO_ID = 1L;
    private static final Long CUENTA_ID = 10L;
    private static final BigDecimal MINIMO = new BigDecimal("1000.00");
    private static final BigDecimal MAXIMO = new BigDecimal("500000.00");

    @Mock private BilleteraRepository billeteraRepository;
    @Mock private CuentaBancariaRepository cuentaBancariaRepository;
    @Mock private MovimientoRepository movimientoRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private ParametrosPlataforma parametrosPlataforma;
    @Mock private AuditoriaService auditoriaService;

    @InjectMocks private RetiroService retiroService;

    @BeforeEach
    void setUp() {
        // En los tests que validan montos, los topes se configuran explícitamente
    }

    @Test
    @DisplayName("solicitar con monto menor al mínimo lanza 400 RETIRO_FUERA_DE_TOPES con detalles")
    void montoMenorAlMinimoLanza400() {
        when(parametrosPlataforma.retiroMinimo()).thenReturn(MINIMO);
        when(parametrosPlataforma.retiroMaximo()).thenReturn(MAXIMO);

        assertThatThrownBy(() -> retiroService.solicitar(USUARIO_ID, CUENTA_ID, new BigDecimal("999.99")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCodigo()).isEqualTo(CodigoError.RETIRO_FUERA_DE_TOPES);
                    assertThat(apiEx.getPropiedades()).containsEntry("minimo", MINIMO);
                    assertThat(apiEx.getPropiedades()).containsEntry("maximo", MAXIMO);
                });

        verifyNoInteractions(cuentaBancariaRepository, billeteraRepository, movimientoRepository);
    }

    @Test
    @DisplayName("solicitar con monto mayor al máximo lanza 400 RETIRO_FUERA_DE_TOPES con detalles")
    void montoMayorAlMaximoLanza400() {
        when(parametrosPlataforma.retiroMinimo()).thenReturn(MINIMO);
        when(parametrosPlataforma.retiroMaximo()).thenReturn(MAXIMO);

        assertThatThrownBy(() -> retiroService.solicitar(USUARIO_ID, CUENTA_ID, new BigDecimal("500000.01")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getCodigo()).isEqualTo(CodigoError.RETIRO_FUERA_DE_TOPES);
                    assertThat(apiEx.getPropiedades()).containsEntry("minimo", MINIMO);
                    assertThat(apiEx.getPropiedades()).containsEntry("maximo", MAXIMO);
                });

        verifyNoInteractions(cuentaBancariaRepository, billeteraRepository, movimientoRepository);
    }

    @Test
    @DisplayName("solicitar con cuenta inexistente lanza 404 CUENTA_BANCARIA_NO_ENCONTRADA")
    void cuentaInexistenteLanza404() {
        when(parametrosPlataforma.retiroMinimo()).thenReturn(MINIMO);
        when(parametrosPlataforma.retiroMaximo()).thenReturn(MAXIMO);
        when(cuentaBancariaRepository.findByIdAndUsuarioId(CUENTA_ID, USUARIO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> retiroService.solicitar(USUARIO_ID, CUENTA_ID, new BigDecimal("5000.00")))
                .isInstanceOf(NoEncontradoException.class)
                .satisfies(ex -> {
                    NoEncontradoException enf = (NoEncontradoException) ex;
                    assertThat(enf.getCodigo()).isEqualTo(CodigoError.CUENTA_BANCARIA_NO_ENCONTRADA);
                });

        verifyNoInteractions(billeteraRepository, movimientoRepository);
    }

    @Test
    @DisplayName("solicitar con cuenta no validada lanza 409 CUENTA_NO_VALIDADA")
    void cuentaNoValidadaLanza409() {
        when(parametrosPlataforma.retiroMinimo()).thenReturn(MINIMO);
        when(parametrosPlataforma.retiroMaximo()).thenReturn(MAXIMO);

        CuentaBancaria cuenta = new CuentaBancaria();
        cuenta.setEstadoValidacion(EstadoValidacion.RECHAZADA);
        when(cuentaBancariaRepository.findByIdAndUsuarioId(CUENTA_ID, USUARIO_ID)).thenReturn(Optional.of(cuenta));

        assertThatThrownBy(() -> retiroService.solicitar(USUARIO_ID, CUENTA_ID, new BigDecimal("5000.00")))
                .isInstanceOf(ConflictoException.class)
                .satisfies(ex -> {
                    ConflictoException ce = (ConflictoException) ex;
                    assertThat(ce.getCodigo()).isEqualTo(CodigoError.CUENTA_NO_VALIDADA);
                });

        verifyNoInteractions(billeteraRepository, movimientoRepository);
    }

    @Test
    @DisplayName("solicitar con billetera bloqueada lanza 403 BILLETERA_BLOQUEADA")
    void billeteraBloqueadaLanza403() {
        when(parametrosPlataforma.retiroMinimo()).thenReturn(MINIMO);
        when(parametrosPlataforma.retiroMaximo()).thenReturn(MAXIMO);

        CuentaBancaria cuenta = cuentaValidada();
        when(cuentaBancariaRepository.findByIdAndUsuarioId(CUENTA_ID, USUARIO_ID)).thenReturn(Optional.of(cuenta));

        Billetera b = new Billetera();
        b.setEstado(EstadoBilletera.BLOQUEADA);
        b.setSaldoDisponible(new BigDecimal("10000.00"));
        when(billeteraRepository.findConBloqueoPorUsuarioId(USUARIO_ID)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> retiroService.solicitar(USUARIO_ID, CUENTA_ID, new BigDecimal("5000.00")))
                .isInstanceOf(ProhibidoException.class)
                .satisfies(ex -> {
                    ProhibidoException pe = (ProhibidoException) ex;
                    assertThat(pe.getCodigo()).isEqualTo(CodigoError.BILLETERA_BLOQUEADA);
                });

        verifyNoInteractions(movimientoRepository);
    }

    @Test
    @DisplayName("solicitar con más del saldo disponible lanza 409 SALDO_INSUFICIENTE aclarando fondos retenidos")
    void saldoDisponibleInsuficienteLanza409ConMensajeFondosRetenidos() {
        when(parametrosPlataforma.retiroMinimo()).thenReturn(MINIMO);
        when(parametrosPlataforma.retiroMaximo()).thenReturn(MAXIMO);

        CuentaBancaria cuenta = cuentaValidada();
        when(cuentaBancariaRepository.findByIdAndUsuarioId(CUENTA_ID, USUARIO_ID)).thenReturn(Optional.of(cuenta));

        Billetera b = new Billetera();
        b.setEstado(EstadoBilletera.ACTIVA);
        b.setSaldoDisponible(new BigDecimal("5000.00"));
        b.setSaldoRetenido(new BigDecimal("20000.00"));
        when(billeteraRepository.findConBloqueoPorUsuarioId(USUARIO_ID)).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> retiroService.solicitar(USUARIO_ID, CUENTA_ID, new BigDecimal("10000.00")))
                .isInstanceOf(ConflictoException.class)
                .satisfies(ex -> {
                    ConflictoException ce = (ConflictoException) ex;
                    assertThat(ce.getCodigo()).isEqualTo(CodigoError.SALDO_INSUFICIENTE);
                    assertThat(ce.getMessage()).isEqualTo(RetiroService.MENSAJE_SALDO_INSUFICIENTE);
                    assertThat(ce.getPropiedades()).containsEntry("saldoDisponible", new BigDecimal("5000.00"));
                    assertThat(ce.getPropiedades()).containsEntry("saldoRetenido", new BigDecimal("20000.00"));
                });

        verifyNoInteractions(movimientoRepository);
    }

    @Test
    @DisplayName("solicitar con saldo suficiente descuenta disponible, guarda movimiento PENDIENTE y audita")
    void retiroValidoDescuentaDisponibleYGuardaMovimiento() {
        when(parametrosPlataforma.retiroMinimo()).thenReturn(MINIMO);
        when(parametrosPlataforma.retiroMaximo()).thenReturn(MAXIMO);

        CuentaBancaria cuenta = cuentaValidada();
        when(cuentaBancariaRepository.findByIdAndUsuarioId(CUENTA_ID, USUARIO_ID)).thenReturn(Optional.of(cuenta));

        Billetera b = new Billetera();
        b.setEstado(EstadoBilletera.ACTIVA);
        b.setSaldoDisponible(new BigDecimal("15000.00"));
        b.setSaldoRetenido(new BigDecimal("2000.00"));
        b.setMoneda("ARS");
        when(billeteraRepository.findConBloqueoPorUsuarioId(USUARIO_ID)).thenReturn(Optional.of(b));

        when(movimientoRepository.saveAndFlush(any(Movimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        Movimiento resultado = retiroService.solicitar(USUARIO_ID, CUENTA_ID, new BigDecimal("5000.00"));

        assertThat(resultado.getTipo()).isEqualTo(TipoMovimiento.RETIRO);
        assertThat(resultado.getEstado()).isEqualTo(EstadoMovimiento.PENDIENTE);
        assertThat(resultado.getMonto()).isEqualByComparingTo("5000.00");
        assertThat(resultado.getSaldoResultante()).isEqualByComparingTo("10000.00");
        assertThat(resultado.getReferenciaExterna()).startsWith("sim-retiro-");
        assertThat(b.getSaldoDisponible()).isEqualByComparingTo("10000.00");

        verify(billeteraRepository).save(b);
        verify(auditoriaService).registrar(
                eq(USUARIO_ID),
                eq(AccionAuditoria.ALTA),
                eq("retiro"),
                any(),
                any());
    }

    @Test
    @DisplayName("confirmarTransferencia cambia estado a CONFIRMADO y audita")
    void confirmarTransferenciaActualizaEstadoYAudita() {
        Usuario u = new Usuario();
        Billetera b = new Billetera();
        b.setUsuario(u);

        Movimiento m = new Movimiento();
        m.setBilletera(b);
        m.setTipo(TipoMovimiento.RETIRO);
        m.setEstado(EstadoMovimiento.PENDIENTE);
        m.setReferenciaExterna("sim-retiro-xyz");

        when(movimientoRepository.findById(99L)).thenReturn(Optional.of(m));
        when(movimientoRepository.saveAndFlush(any(Movimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        Movimiento confirmado = retiroService.confirmarTransferencia(99L);

        assertThat(confirmado.getEstado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        verify(auditoriaService).registrar(
                any(),
                eq(AccionAuditoria.MODIFICACION),
                eq("retiro"),
                any(),
                any());
    }

    private CuentaBancaria cuentaValidada() {
        CuentaBancaria cuenta = new CuentaBancaria();
        cuenta.setTipo(TipoCuenta.CBU);
        cuenta.setValor("2850590940090418135201");
        cuenta.setTitular("Juan Perez");
        cuenta.setCuitCuil("20-35123456-9");
        cuenta.setEstadoValidacion(EstadoValidacion.VALIDADA);
        return cuenta;
    }
}
