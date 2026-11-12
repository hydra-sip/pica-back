package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.CuentaBancaria;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoValidacion;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;
import com.hydra.pica.plataforma_pica.billetera.dto.CrearCuentaBancariaRequest;
import com.hydra.pica.plataforma_pica.billetera.dto.CuentaBancariaResponse;
import com.hydra.pica.plataforma_pica.billetera.repository.CuentaBancariaRepository;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class CuentaBancariaServiceTest {

    private static final String CBU_VALIDO = "2850590940090418135201";
    private static final String DNI_USUARIO = "35123456";
    private static final String CUIT_COINCIDENTE = "20-35123456-9";
    private static final String CUIT_OTRO = "20-40999888-1";

    @Mock private CuentaBancariaRepository cuentaBancariaRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private AuditoriaService auditoriaService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private CuentaBancariaService servicio;

    @BeforeEach
    void setUp() {
        servicio = new CuentaBancariaService(
                cuentaBancariaRepository,
                usuarioRepository,
                auditoriaService,
                transactionManager);
    }

    @Test
    @DisplayName("crear con formato inválido lanza 400 VALIDACION con el campo correspondiente")
    void crearFormatoInvalidoLanza400() {
        CrearCuentaBancariaRequest reqCbuMalo = new CrearCuentaBancariaRequest(
                TipoCuenta.CBU, "12345", "Marcos Paz", CUIT_COINCIDENTE);

        assertThatThrownBy(() -> servicio.crear(1L, reqCbuMalo))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    @SuppressWarnings("unchecked")
                    var errores = (java.util.List<ErrorCampo>) ae.getPropiedades().get("errores");
                    assertThat(errores).anyMatch(e -> e.campo().equals("valor") && e.codigo() == ErrorCampo.Codigo.FORMATO_INVALIDO);
                });

        CrearCuentaBancariaRequest reqAliasCorto = new CrearCuentaBancariaRequest(
                TipoCuenta.ALIAS, "alias", "Marcos Paz", CUIT_COINCIDENTE);

        assertThatThrownBy(() -> servicio.crear(1L, reqAliasCorto))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    @SuppressWarnings("unchecked")
                    var errores = (java.util.List<ErrorCampo>) ae.getPropiedades().get("errores");
                    assertThat(errores).anyMatch(e -> e.campo().equals("valor") && e.codigo() == ErrorCampo.Codigo.LONGITUD);
                });

        CrearCuentaBancariaRequest reqCuitMalo = new CrearCuentaBancariaRequest(
                TipoCuenta.CBU, CBU_VALIDO, "Marcos Paz", "123");

        assertThatThrownBy(() -> servicio.crear(1L, reqCuitMalo))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException ae = (ApiException) ex;
                    assertThat(ae.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    @SuppressWarnings("unchecked")
                    var errores = (java.util.List<ErrorCampo>) ae.getPropiedades().get("errores");
                    assertThat(errores).anyMatch(e -> e.campo().equals("cuitCuil") && e.codigo() == ErrorCampo.Codigo.FORMATO_INVALIDO);
                });
    }

    @Test
    @DisplayName("crear con cuenta duplicada ya activa lanza 409 CUENTA_BANCARIA_DUPLICADA")
    void crearCuentaDuplicadaLanza409() {
        Usuario usuario = usuarioConDni(1L, DNI_USUARIO);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));

        CuentaBancaria existente = new CuentaBancaria();
        existente.setEstadoValidacion(EstadoValidacion.VALIDADA);
        when(cuentaBancariaRepository.findByUsuarioIdAndValorIgnoreCase(1L, CBU_VALIDO))
                .thenReturn(Optional.of(existente));

        CrearCuentaBancariaRequest req = new CrearCuentaBancariaRequest(
                TipoCuenta.CBU, CBU_VALIDO, "Marcos Paz", CUIT_COINCIDENTE);

        assertThatThrownBy(() -> servicio.crear(1L, req))
                .isInstanceOf(ConflictoException.class)
                .satisfies(ex -> {
                    ConflictoException ce = (ConflictoException) ex;
                    assertThat(ce.getCodigo()).isEqualTo(CodigoError.CUENTA_BANCARIA_DUPLICADA);
                });
    }

    @Test
    @DisplayName("crear con titular que no coincide con el DNI del usuario lanza 409 con mensaje exacto y persiste RECHAZADA")
    void crearTitularNoCoincideLanza409YPersisteRechazo() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        Usuario usuario = usuarioConDni(1L, DNI_USUARIO);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));
        when(cuentaBancariaRepository.findByUsuarioIdAndValorIgnoreCase(1L, CBU_VALIDO))
                .thenReturn(Optional.empty());

        CuentaBancaria guardada = new CuentaBancaria();
        guardada.setEstadoValidacion(EstadoValidacion.RECHAZADA);
        when(cuentaBancariaRepository.saveAndFlush(any(CuentaBancaria.class))).thenReturn(guardada);

        CrearCuentaBancariaRequest req = new CrearCuentaBancariaRequest(
                TipoCuenta.CBU, CBU_VALIDO, "Juan Perez", CUIT_OTRO);

        assertThatThrownBy(() -> servicio.crear(1L, req))
                .isInstanceOf(ConflictoException.class)
                .satisfies(ex -> {
                    ConflictoException ce = (ConflictoException) ex;
                    assertThat(ce.getCodigo()).isEqualTo(CodigoError.CUENTA_TITULAR_NO_COINCIDE);
                    assertThat(ce.getMessage()).isEqualTo(CuentaBancariaService.MENSAJE_TITULAR_NO_COINCIDE);
                });

        verify(cuentaBancariaRepository).saveAndFlush(any(CuentaBancaria.class));
    }

    @Test
    @DisplayName("crear con titular coincidente valida la cuenta, la guarda y audita")
    void crearTitularCoincidenteQuedaValidada() {
        Usuario usuario = usuarioConDni(1L, DNI_USUARIO);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));
        when(cuentaBancariaRepository.findByUsuarioIdAndValorIgnoreCase(1L, CBU_VALIDO))
                .thenReturn(Optional.empty());

        CuentaBancaria guardada = new CuentaBancaria();
        guardada.setUsuario(usuario);
        guardada.setTipo(TipoCuenta.CBU);
        guardada.setValor(CBU_VALIDO);
        guardada.setTitular("Marcos Paz");
        guardada.setCuitCuil(CUIT_COINCIDENTE);
        guardada.setEstadoValidacion(EstadoValidacion.VALIDADA);
        when(cuentaBancariaRepository.save(any(CuentaBancaria.class))).thenReturn(guardada);

        CrearCuentaBancariaRequest req = new CrearCuentaBancariaRequest(
                TipoCuenta.CBU, CBU_VALIDO, "Marcos Paz", CUIT_COINCIDENTE);

        CuentaBancariaResponse res = servicio.crear(1L, req);

        assertThat(res.estadoValidacion()).isEqualTo(EstadoValidacion.VALIDADA);
        assertThat(res.valor()).isEqualTo(CBU_VALIDO);
        verify(cuentaBancariaRepository).save(any(CuentaBancaria.class));
        verify(auditoriaService).registrar(eq(1L), eq(AccionAuditoria.ALTA), eq("cuenta_bancaria"), any(), any());
    }

    @Test
    @DisplayName("eliminar cuenta inexistente o de otro usuario lanza 404 CUENTA_BANCARIA_NO_ENCONTRADA")
    void eliminarCuentaInexistenteLanza404() {
        when(cuentaBancariaRepository.findByIdAndUsuarioId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.eliminar(1L, 99L))
                .isInstanceOf(NoEncontradoException.class)
                .satisfies(ex -> {
                    NoEncontradoException ne = (NoEncontradoException) ex;
                    assertThat(ne.getCodigo()).isEqualTo(CodigoError.CUENTA_BANCARIA_NO_ENCONTRADA);
                });
    }

    @Test
    @DisplayName("eliminar cuenta propia realiza baja lógica con eliminadoEn y audita")
    void eliminarCuentaPropiaRealizaBajaLogica() {
        CuentaBancaria cuenta = new CuentaBancaria();
        cuenta.setTipo(TipoCuenta.ALIAS);
        cuenta.setValor("mi.alias.pica");
        when(cuentaBancariaRepository.findByIdAndUsuarioId(10L, 1L)).thenReturn(Optional.of(cuenta));

        servicio.eliminar(1L, 10L);

        assertThat(cuenta.getEliminadoEn()).isNotNull();
        verify(cuentaBancariaRepository).save(cuenta);
        verify(auditoriaService).registrar(eq(1L), eq(AccionAuditoria.BAJA), eq("cuenta_bancaria"), any(), any());
    }

    private static Usuario usuarioConDni(Long id, String dni) {
        Persona persona = new Persona();
        persona.setNroDoc(dni);

        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        return usuario;
    }
}

