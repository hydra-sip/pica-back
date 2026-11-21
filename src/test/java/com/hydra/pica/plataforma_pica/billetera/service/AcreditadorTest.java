package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.hydra.pica.plataforma_pica.billetera.domain.Billetera;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.repository.BilleteraRepository;
import com.hydra.pica.plataforma_pica.billetera.repository.MovimientoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.control.domain.AccionAuditoria;
import com.hydra.pica.plataforma_pica.control.service.AuditoriaService;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class AcreditadorTest {

    private static final Long USUARIO_1 = 1L;
    private static final Long USUARIO_2 = 2L;
    private static final Long USUARIO_3 = 3L;
    private static final Acreditador.Evento EVENTO_GENERICO = new Acreditador.Evento(null, null, "TORNEO", 1L);

    @Mock private BilleteraRepository billeteraRepository;
    @Mock private MovimientoRepository movimientoRepository;
    @Mock private TorneoRepository torneoRepository;
    @Mock private RifaRepository rifaRepository;
    @Mock private InscripcionRepository inscripcionRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private AuditoriaService auditoriaService;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks private Acreditador acreditador;

    @Test
    @DisplayName("acreditar procesa por usuarioId ascendente, con la de plataforma (null) al final")
    void ordenaPorUsuarioIdConPlataformaAlFinal() {
        conBilleteraDeUsuario(USUARIO_1);
        conBilleteraDeUsuario(USUARIO_2);
        conBilleteraDeUsuario(USUARIO_3);
        conBilleteraDePlataforma();

        List<Acreditacion> desordenadas = List.of(
                acreditacion(Rol.PREMIO, USUARIO_3, "10.00", null, null),
                acreditacion(Rol.PLATAFORMA, null, "10.00", null, null),
                acreditacion(Rol.PREMIO, USUARIO_1, "10.00", null, null),
                acreditacion(Rol.PREMIO, USUARIO_2, "10.00", null, null));

        acreditador.acreditar(EVENTO_GENERICO, desordenadas);

        InOrder orden = Mockito.inOrder(billeteraRepository);
        orden.verify(billeteraRepository).findConBloqueoPorUsuarioId(USUARIO_1);
        orden.verify(billeteraRepository).findConBloqueoPorUsuarioId(USUARIO_2);
        orden.verify(billeteraRepository).findConBloqueoPorUsuarioId(USUARIO_3);
        orden.verify(billeteraRepository).findPlataformaConBloqueo();
    }

    @ParameterizedTest(name = "descripcion de {0} caracteres queda truncada a 255")
    @ValueSource(ints = {255, 300})
    void truncaDescripcionA255SiEsMasLarga(int longitud) {
        conBilleteraDeUsuario(USUARIO_1);
        String descripcion = "x".repeat(longitud);

        acreditador.acreditar(EVENTO_GENERICO,
                List.of(acreditacion(Rol.PREMIO, USUARIO_1, "10.00", null, null, descripcion)));

        assertThat(movimientoGuardado().getDescripcion()).isEqualTo(descripcion.substring(0, 255));
    }

    @ParameterizedTest(name = "rol {0} mapea a TipoMovimiento {1}")
    @CsvSource({
            "PREMIO, PREMIO",
            "HONORARIO, HONORARIO",
            "ORGANIZADOR, LIQUIDACION",
            "PLATAFORMA, LIQUIDACION"
    })
    void mapeaRolATipoMovimiento(Rol rol, TipoMovimiento esperado) {
        Long usuarioId = rol == Rol.PLATAFORMA ? null : USUARIO_1;
        conBilletera(rol, usuarioId);

        acreditador.acreditar(EVENTO_GENERICO, List.of(acreditacion(rol, usuarioId, "10.00", null, null)));

        assertThat(movimientoGuardado().getTipo()).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "puesto={0} inscripcionId={1}")
    @MethodSource("puestoEInscripcion")
    void detalleIncluyePuestoEInscripcionSoloCuandoNoSonNulos(Integer puesto, Long inscripcionId) {
        conBilleteraDeUsuario(USUARIO_1);

        acreditador.acreditar(EVENTO_GENERICO,
                List.of(acreditacion(Rol.PREMIO, USUARIO_1, "10.00", inscripcionId, puesto)));

        Map<String, Object> detalle = detalleAuditado(EVENTO_GENERICO);
        assertThat(detalle).containsKeys("tipo", "rol", "movimientoId", "usuarioId", "monto");
        if (puesto != null) {
            assertThat(detalle).containsEntry("puesto", puesto);
        } else {
            assertThat(detalle).doesNotContainKey("puesto");
        }
        if (inscripcionId != null) {
            assertThat(detalle).containsEntry("inscripcionId", inscripcionId);
        } else {
            assertThat(detalle).doesNotContainKey("inscripcionId");
        }
    }

    private static Stream<Arguments> puestoEInscripcion() {
        return Stream.of(Arguments.of(2, 55L), Arguments.of(null, null));
    }

    @ParameterizedTest(name = "detalle.tipo = {3}")
    @MethodSource("tiposDeDetalle")
    void detalleTipoSegunRolYEvento(Acreditador.Evento evento, Rol rol, Long usuarioId, String tipoEsperado) {
        conBilletera(rol, usuarioId);
        if (evento.torneoId() != null) {
            when(torneoRepository.getReferenceById(evento.torneoId())).thenReturn(new Torneo());
        }
        if (evento.rifaId() != null) {
            when(rifaRepository.getReferenceById(evento.rifaId())).thenReturn(new Rifa());
        }

        acreditador.acreditar(evento, List.of(acreditacion(rol, usuarioId, "10.00", null, null)));

        assertThat(detalleAuditado(evento).get("tipo")).isEqualTo(tipoEsperado);
    }

    private static Stream<Arguments> tiposDeDetalle() {
        return Stream.of(
                Arguments.of(EVENTO_GENERICO, Rol.PREMIO, USUARIO_1, "LIQUIDACION_PREMIO"),
                Arguments.of(EVENTO_GENERICO, Rol.HONORARIO, USUARIO_1, "LIQUIDACION_HONORARIO"),
                Arguments.of(Acreditador.Evento.torneo(100L), Rol.ORGANIZADOR, USUARIO_1, "LIQUIDACION_TORNEO"),
                Arguments.of(Acreditador.Evento.rifa(50L), Rol.PLATAFORMA, null, "LIQUIDACION_RIFA"));
    }

    @Test
    @DisplayName("bloqueada crea la billetera si no existe y la devuelve en la segunda busqueda")
    void bloqueadaCreaBilleteraSiNoExiste() {
        when(billeteraRepository.findConBloqueoPorUsuarioId(USUARIO_1))
                .thenReturn(Optional.empty(), Optional.of(new Billetera()));
        when(usuarioRepository.getReferenceById(USUARIO_1)).thenReturn(new Usuario());
        when(movimientoRepository.save(any(Movimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        acreditador.acreditar(EVENTO_GENERICO, List.of(acreditacion(Rol.PREMIO, USUARIO_1, "10.00", null, null)));

        verify(billeteraRepository, times(2)).findConBloqueoPorUsuarioId(USUARIO_1);
        verify(billeteraRepository).saveAndFlush(any(Billetera.class));
    }

    @Test
    @DisplayName("sin billetera de plataforma lanza IllegalStateException sin efectos secundarios")
    void sinBilleteraDePlataformaLanzaExcepcionSinEfectosSecundarios() {
        when(billeteraRepository.findPlataformaConBloqueo()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> acreditador.acreditar(EVENTO_GENERICO,
                List.of(acreditacion(Rol.PLATAFORMA, null, "10.00", null, null))))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(movimientoRepository, auditoriaService);
    }

    @Test
    @DisplayName("Evento.aporte vincula el movimiento a torneo y rifa, y audita con entidad RIFA")
    void eventoAporteVinculaMovimientoATorneoYRifaYAuditaComoRifa() {
        conBilleteraDeUsuario(USUARIO_1);
        Torneo torneo = new Torneo();
        Rifa rifa = new Rifa();
        when(torneoRepository.getReferenceById(100L)).thenReturn(torneo);
        when(rifaRepository.getReferenceById(50L)).thenReturn(rifa);
        Acreditador.Evento aporte = Acreditador.Evento.aporte(100L, 50L);

        acreditador.acreditar(aporte, List.of(acreditacion(Rol.ORGANIZADOR, USUARIO_1, "10.00", null, null)));

        assertThat(movimientoGuardado().getTorneo()).isSameAs(torneo);
        assertThat(movimientoGuardado().getRifa()).isSameAs(rifa);
        detalleAuditado(aporte); // la verificación ya exige entidad="RIFA" y entidadId=50
    }

    @Test
    @DisplayName("saldoDisponible, gananciaAcumulada y saldoResultante reflejan la suma tras acreditar")
    void saldoYGananciaReflejanLaSumaTrasAcreditar() {
        Billetera billetera = conBilleteraDeUsuario(USUARIO_1);
        billetera.setSaldoDisponible(new BigDecimal("1000.00"));
        billetera.setGananciaAcumulada(new BigDecimal("500.00"));

        acreditador.acreditar(EVENTO_GENERICO, List.of(acreditacion(Rol.PREMIO, USUARIO_1, "200.00", null, null)));

        assertThat(billetera.getSaldoDisponible()).isEqualByComparingTo("1200.00");
        assertThat(billetera.getGananciaAcumulada()).isEqualByComparingTo("700.00");
        verify(billeteraRepository).save(billetera);
        assertThat(movimientoGuardado().getSaldoResultante()).isEqualByComparingTo("1200.00");
    }

    @ParameterizedTest(name = "suma de {0} acreditaciones = {1}")
    @MethodSource("listasYSumas")
    void sumaDevuelveLaSumaDeLosMontos(List<Acreditacion> acreditaciones, BigDecimal esperado) {
        assertThat(Acreditador.suma(acreditaciones)).isEqualByComparingTo(esperado);
    }

    private static Stream<Arguments> listasYSumas() {
        return Stream.of(
                Arguments.of(List.of(
                        acreditacion(Rol.PREMIO, USUARIO_1, "10.50", null, null),
                        acreditacion(Rol.PREMIO, USUARIO_2, "20.25", null, null)), new BigDecimal("30.75")),
                Arguments.of(List.of(), BigDecimal.ZERO));
    }

    @Test
    @DisplayName("alertar publica el detalle del reparto que no cuadra en su propia transaccion")
    void alertarPublicaDetalleDelRepartoQueNoCuadra() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        acreditador.alertar(EVENTO_GENERICO, 77L, new BigDecimal("500.00"), new BigDecimal("300.00"));

        Map<String, Object> detalle = detalleAuditado(EVENTO_GENERICO);
        assertThat(detalle.get("alerta")).isEqualTo("REPARTO_NO_CUADRA");
        assertThat(detalle.get("custodiaId")).isEqualTo(77L);
        assertThat(detalle.get("retenido")).isEqualTo(new BigDecimal("500.00"));
        assertThat(detalle.get("repartido")).isEqualTo(new BigDecimal("300.00"));
    }

    /** Resuelve la billetera según el rol y deja el save del movimiento listo para el camino feliz. */
    private void conBilletera(Rol rol, Long usuarioId) {
        if (rol == Rol.PLATAFORMA) {
            conBilleteraDePlataforma();
        } else {
            conBilleteraDeUsuario(usuarioId);
        }
    }

    private Billetera conBilleteraDeUsuario(Long usuarioId) {
        Billetera billetera = new Billetera();
        when(billeteraRepository.findConBloqueoPorUsuarioId(usuarioId)).thenReturn(Optional.of(billetera));
        conMovimientoGuardado();
        return billetera;
    }

    private Billetera conBilleteraDePlataforma() {
        Billetera billetera = new Billetera();
        when(billeteraRepository.findPlataformaConBloqueo()).thenReturn(Optional.of(billetera));
        conMovimientoGuardado();
        return billetera;
    }

    private void conMovimientoGuardado() {
        Mockito.lenient().when(movimientoRepository.save(any(Movimiento.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Movimiento movimientoGuardado() {
        ArgumentCaptor<Movimiento> captor = ArgumentCaptor.forClass(Movimiento.class);
        verify(movimientoRepository).save(captor.capture());
        return captor.getValue();
    }

    private Map<String, Object> detalleAuditado(Acreditador.Evento evento) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(auditoriaService).registrar(isNull(), eq(AccionAuditoria.LIQUIDACION), eq(evento.entidad()),
                eq(evento.entidadId().toString()), captor.capture());
        return captor.getValue();
    }

    private static Acreditacion acreditacion(Rol rol, Long usuarioId, String monto, Long inscripcionId,
            Integer puesto) {
        return acreditacion(rol, usuarioId, monto, inscripcionId, puesto, "liquidacion");
    }

    private static Acreditacion acreditacion(Rol rol, Long usuarioId, String monto, Long inscripcionId,
            Integer puesto, String descripcion) {
        return new Acreditacion(rol, usuarioId, new BigDecimal(monto), inscripcionId, puesto,
                "ref-" + rol + "-" + usuarioId, descripcion);
    }
}
