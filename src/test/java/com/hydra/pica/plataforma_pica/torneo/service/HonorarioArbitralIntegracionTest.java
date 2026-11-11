package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.service.LiquidacionService;
import com.hydra.pica.plataforma_pica.billetera.service.RepartoLiquidacionService;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoPagoGasto;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoGasto;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.HonorarioDesignacion;
import com.hydra.pica.plataforma_pica.torneo.dto.MiDesignacion;
import com.hydra.pica.plataforma_pica.torneo.dto.PagoGastoRequest;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * HU-604 (PICA-350): la identidad del árbitro y el honorario en la custodia del torneo (wireframe 49). Cuatro jugadores
 * pagan con saldo $1000 más 10 % (hay $4400 en custodia), el torneo tiene un gasto común de $500 (la cancha) y un
 * honorario arbitral de $3000, y premia 100 % al 1.º. El organizador tiene $50.000 y "juez_ok" (identidad verificada)
 * está designado. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class HonorarioArbitralIntegracionTest {

    private static final int CANCHA = 1;
    private static final int HONORARIO = 2;
    private static final AtomicInteger DOCUMENTOS = new AtomicInteger(30_000_000);

    @Autowired private HonorariosService honorariosService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private DesignacionesService designacionesService;
    @Autowired private EconomiaTorneoService economiaService;
    @Autowired private LiquidacionService liquidacionService;
    @Autowired private RepartoLiquidacionService repartoLiquidacionService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long juez;
    private long torneo;
    private final Long[] jugador = new Long[4];
    private final long[] inscripcion = new long[4];

    @BeforeEach
    void escenario() {
        organizador = usuario("org_hon", "ORGANIZADOR", false);
        saldo(organizador, "50000.00");
        juez = usuario("juez_ok", "ARBITRO", true);
        torneo = torneo(1000);
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 100)", torneo);
        jdbc.update("""
                INSERT INTO gasto (torneo_id, gasto_id, descripcion, monto, tipo)
                VALUES (?, 1, 'Cancha', 500, 'OTRO'), (?, 2, 'Árbitro', 3000, 'HONORARIO_ARBITRAL')
                """, torneo, torneo);
        for (int i = 0; i < 4; i++) {
            jugador[i] = usuario("jug_hon_" + i, "PARTICIPANTE", false);
            saldo(jugador[i], "5000.00");
            inscripcion[i] = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                    VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                    """, Long.class, torneo, jugador[i]);
            inscripcionService.pagar(jugador[i], inscripcion[i]);
        }
        designar(torneo, juez);
        entityManager.flush();
    }

    // --- la identidad -------------------------------------------------------------------------------

    @Test
    void enUnTorneoConDineroNoSeDesignaAQuienNoVerificoSuIdentidad() {
        Long sinVerificar = usuario("juez_sin_dni", "ARBITRO", false);

        assertThatThrownBy(() -> designar(torneo, sinVerificar))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.IDENTIDAD_NO_VERIFICADA);
                    assertThat(e.getMessage()).isEqualTo(
                            "El usuario propuesto debe verificar su identidad antes de arbitrar torneos con dinero");
                });
    }

    @Test
    void enUnTorneoGratisSiSeLoDesignaYElBuscadorDiceQuienEstaVerificado() {
        Long sinVerificar = usuario("juez_sin_dni", "ARBITRO", false);
        long gratis = torneo(0);

        designar(gratis, sinVerificar);

        assertThat(arbitrosService.buscar("juez", 0, 20).getContent())
                .extracting(a -> a.username() + ":" + a.identidadVerificada())
                .contains("juez_ok:true", "juez_sin_dni:false");
        assertThat(arbitrosService.listar(organizador, torneo).get(0).identidadVerificada()).isTrue();
    }

    @Test
    void sinDocumentoOFechaDeNacimientoNoHayIdentidadVerificada() {
        Usuario usuario = usuarioRepository.findById(juez).orElseThrow();
        assertThat(usuario.identidadVerificada()).isTrue();
        usuario.getPersona().setFechaNacimiento(null);
        assertThat(usuario.identidadVerificada()).isFalse();
        usuario.getPersona().setFechaNacimiento(LocalDate.of(1990, 1, 1));
        usuario.setEmailVerificado(false);
        assertThat(usuario.identidadVerificada()).isFalse();
    }

    // --- el depósito --------------------------------------------------------------------------------

    @Test
    void elHonorarioSaleDelDisponibleDelOrganizadorYQuedaEnLaCustodia() {
        GastoDetalle pagado = depositar();

        assertThat(pagado.estadoPago()).isEqualTo(EstadoPagoGasto.EN_CUSTODIA);
        assertThat(pagado.tipo()).isEqualTo(TipoGasto.HONORARIO_ARBITRAL);
        assertThat(pagado.beneficiario().username()).isEqualTo("juez_ok");
        assertThat(pagado.abonadoEn()).isNotNull();
        assertBilletera(organizador, "47000.00", "3000.00");
        assertThat(jdbc.queryForMap("""
                SELECT m.tipo, m.monto, m.estado, m.torneo_id, c.torneo_id AS custodia_torneo
                FROM gasto g JOIN movimiento m ON m.id = g.movimiento_id JOIN custodia c ON c.id = m.custodia_id
                WHERE g.torneo_id = ? AND g.gasto_id = ?
                """, torneo, HONORARIO))
                .containsEntry("tipo", "PAGO_SERVICIO")
                .containsEntry("estado", "CONFIRMADO")
                .containsEntry("torneo_id", torneo)
                .containsEntry("custodia_torneo", torneo);
    }

    @Test
    void elArbitroVeElHonorarioRetenidoEnSuDesignacion() {
        assertThat(miDesignacion().honorario()).isNull();

        depositar();

        HonorarioDesignacion honorario = miDesignacion().honorario();
        assertThat(honorario.monto()).isEqualByComparingTo("3000.00");
        assertThat(honorario.estado()).isEqualTo(EstadoPagoGasto.EN_CUSTODIA);
    }

    @Test
    void sinSaldoNoSeDepositaNada() {
        saldo(organizador, "100.00");

        assertError(this::depositar, HttpStatus.CONFLICT, CodigoError.SALDO_INSUFICIENTE);
        assertBilletera(organizador, "100.00", "0.00");
    }

    @Test
    void unHonorarioNoSeDepositaDosVeces() {
        depositar();

        assertError(this::depositar, HttpStatus.CONFLICT, CodigoError.HONORARIO_YA_DEPOSITADO);
        assertBilletera(organizador, "47000.00", "3000.00");
    }

    @Test
    void soloSeDepositaParaUnArbitroDesignadoQueNoRechazo() {
        Long otro = usuario("juez_otro", "ARBITRO", true);
        assertError(() -> honorariosService.pagar(organizador, torneo, HONORARIO, new PagoGastoRequest(otro, null)),
                HttpStatus.NOT_FOUND, CodigoError.ARBITRO_NO_ENCONTRADO);

        designacionesService.rechazar(juez, torneo, "No puedo ese día");
        assertError(this::depositar, HttpStatus.NOT_FOUND, CodigoError.ARBITRO_NO_ENCONTRADO);
    }

    @Test
    void unGastoComunNoSeDepositaYSeRegistraConSuComprobante() {
        assertError(() -> honorariosService.pagar(organizador, torneo, CANCHA, new PagoGastoRequest(juez, null)),
                HttpStatus.CONFLICT, CodigoError.GASTO_NO_ES_HONORARIO);

        GastoDetalle cancha = honorariosService.pagar(organizador, torneo, CANCHA,
                new PagoGastoRequest(null, " Factura B 0001-00001234 "));

        assertThat(cancha.estadoPago()).isEqualTo(EstadoPagoGasto.PAGADO);
        assertThat(cancha.comprobante()).isEqualTo("Factura B 0001-00001234");
        assertError(() -> honorariosService.pagar(organizador, torneo, CANCHA, new PagoGastoRequest(null, "Otra")),
                HttpStatus.CONFLICT, CodigoError.GASTO_YA_PAGADO);
        assertBilletera(organizador, "50000.00", "0.00");
    }

    @Test
    void unHonorarioConUnComprobanteExternoNoEsDeducible() {
        assertThatThrownBy(() -> honorariosService.pagar(organizador, torneo, HONORARIO,
                new PagoGastoRequest(null, "Transferencia bancaria 123")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.HONORARIO_NO_DEDUCIBLE);
                    assertThat(e.getMessage()).isEqualTo("Los honorarios arbitrales solo son deducibles si se "
                            + "abonan mediante transferencia interna de la plataforma");
                });
    }

    @Test
    void vaElArbitroOElComprobanteUnoDeLosDos() {
        assertError(() -> honorariosService.pagar(organizador, torneo, HONORARIO, new PagoGastoRequest(null, " ")),
                HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
        assertError(() -> honorariosService.pagar(organizador, torneo, HONORARIO, new PagoGastoRequest(juez, "x")),
                HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
    }

    @Test
    void conElTorneoTerminadoYaNoSePaga() {
        estado("FINALIZADO");

        assertError(this::depositar, HttpStatus.CONFLICT, CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void otroOrganizadorNoLoVe() {
        Long otro = usuario("org_otro", "ORGANIZADOR", false);

        assertError(() -> honorariosService.pagar(otro, torneo, HONORARIO, new PagoGastoRequest(juez, null)),
                HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void unHonorarioDepositadoNoSeModificaNiSeBorra() {
        estado("BORRADOR");
        depositar();

        assertError(() -> economiaService.modificarGasto(organizador, torneo, HONORARIO,
                        new GastoRequest("Árbitro", new BigDecimal("1"), TipoGasto.HONORARIO_ARBITRAL)),
                HttpStatus.CONFLICT, CodigoError.HONORARIO_YA_DEPOSITADO);
        assertError(() -> economiaService.eliminarGasto(organizador, torneo, HONORARIO),
                HttpStatus.CONFLICT, CodigoError.HONORARIO_YA_DEPOSITADO);
    }

    @Test
    void elPaso3GuardaSiElGastoEsUnHonorario() {
        estado("BORRADOR");

        GastoDetalle nuevo = economiaService.agregarGasto(organizador, torneo,
                new GastoRequest("Segundo árbitro", new BigDecimal("2000"), TipoGasto.HONORARIO_ARBITRAL));
        GastoDetalle comun = economiaService.agregarGasto(organizador, torneo,
                new GastoRequest("Pelotas", new BigDecimal("800")));

        assertThat(nuevo.tipo()).isEqualTo(TipoGasto.HONORARIO_ARBITRAL);
        assertThat(nuevo.estadoPago()).isEqualTo(EstadoPagoGasto.PENDIENTE);
        assertThat(comun.tipo()).isEqualTo(TipoGasto.OTRO);
    }

    // --- la devolución ------------------------------------------------------------------------------

    @Test
    void siElArbitroRechazaElHonorarioVuelveAlOrganizador() {
        depositar();

        designacionesService.rechazar(juez, torneo, "No puedo ese día");

        assertDevuelto("el árbitro rechazó la designación");
    }

    @Test
    void siElOrganizadorQuitaAlArbitroElHonorarioVuelve() {
        depositar();

        arbitrosService.quitar(organizador, torneo, juez);

        assertDevuelto("el organizador lo quitó del torneo");
    }

    @Test
    void elOrganizadorRetiraElDepositoYPuedeVolverADepositar() {
        depositar();

        GastoDetalle retirado = honorariosService.retirar(organizador, torneo, HONORARIO);

        assertThat(retirado.estadoPago()).isEqualTo(EstadoPagoGasto.PENDIENTE);
        assertDevuelto("lo retiró el organizador");
        assertError(() -> honorariosService.retirar(organizador, torneo, HONORARIO), HttpStatus.CONFLICT,
                CodigoError.HONORARIO_SIN_DEPOSITAR);

        assertThat(depositar().estadoPago()).isEqualTo(EstadoPagoGasto.EN_CUSTODIA);
        assertBilletera(organizador, "47000.00", "3000.00");
    }

    // --- la liquidación -----------------------------------------------------------------------------

    @Test
    void laLiquidacionLeAcreditaElHonorarioAlArbitroYLeDevuelveElGastoAlOrganizador() {
        depositar();
        terminar();

        liquidacionService.liquidarTorneo(torneo);

        // custodia: 4400 de los jugadores y 3000 del organizador
        assertBilletera(juez, "3000.00", "0.00");
        assertBilletera(organizador, "50500.00", "0.00");   // 47000 + 500 de la cancha + 3000 del honorario
        assertBilletera(jugador[0], "4400.00", "0.00");     // 3900 + el pozo: 4000 - 3500 de gastos
        assertThat(jdbc.queryForMap("""
                SELECT m.tipo, m.monto, m.torneo_id FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = ?
                """, juez)).containsEntry("tipo", "HONORARIO").containsEntry("torneo_id", torneo);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auditoria
                WHERE accion = 'LIQUIDACION' AND entidad_id = ? AND detalle->>'tipo' = 'LIQUIDACION_HONORARIO'
                """, Long.class, String.valueOf(torneo))).isEqualTo(1L);

        entityManager.flush();
        entityManager.clear();
        assertThat(economiaService.gastos(organizador, torneo)).extracting(GastoDetalle::estadoPago)
                .containsExactly(EstadoPagoGasto.PENDIENTE, EstadoPagoGasto.ACREDITADO);
        assertThat(miDesignacion().honorario().estado()).isEqualTo(EstadoPagoGasto.ACREDITADO);
        RepartoLiquidacion reparto = repartoLiquidacionService.delTorneo(organizador, torneo);
        assertThat(reparto.total()).isEqualByComparingTo("7400.00");
        assertThat(reparto.lineas()).extracting(l -> l.rol() + ":" + l.beneficiarioUsername() + ":"
                        + l.monto().toPlainString())
                .contains("HONORARIO:juez_ok:3000.00", "ORGANIZADOR:org_hon:3500.00");
    }

    @Test
    void unHonorarioSinDepositarNoSeDeduceYQuedaEnElPozo() {
        terminar();

        liquidacionService.liquidarTorneo(torneo);

        assertBilletera(organizador, "50500.00", "0.00");   // solo la cancha
        assertBilletera(jugador[0], "7400.00", "0.00");     // 3900 + 3500
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM movimiento m JOIN billetera b ON b.id = m.billetera_id WHERE b.usuario_id = ?
                """, Long.class, juez)).isZero();
    }

    @Test
    void unDepositoRetiradoNoCuentaEnLaLiquidacion() {
        depositar();
        honorariosService.retirar(organizador, torneo, HONORARIO);
        terminar();

        liquidacionService.liquidarTorneo(torneo);

        assertBilletera(organizador, "50500.00", "0.00");
        assertBilletera(jugador[0], "7400.00", "0.00");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo))
                .isEqualTo("LIBERADA");
    }

    // --- auxiliares ---------------------------------------------------------------------------------

    private GastoDetalle depositar() {
        return honorariosService.pagar(organizador, torneo, HONORARIO, new PagoGastoRequest(juez, null));
    }

    private MiDesignacion miDesignacion() {
        return designacionesService.mias(juez).stream().filter(d -> d.torneoId() == torneo).findFirst()
                .orElseThrow();
    }

    private void assertDevuelto(String motivo) {
        assertBilletera(organizador, "50000.00", "0.00");
        assertThat(jdbc.queryForList("""
                SELECT m.tipo || ':' || m.estado FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = ? ORDER BY m.id
                """, String.class, organizador)).containsSubsequence("PAGO_SERVICIO:REVERTIDO", "REINTEGRO:CONFIRMADO");
        assertThat(jdbc.queryForMap("""
                SELECT tipo, movimiento_id, beneficiario_id, monto_abonado FROM gasto
                WHERE torneo_id = ? AND gasto_id = ?
                """, torneo, HONORARIO)).containsEntry("movimiento_id", null).containsEntry("beneficiario_id", null);
        assertThat(jdbc.queryForObject("""
                SELECT detalle->>'motivo' FROM auditoria
                WHERE accion = 'PAGO' AND detalle->>'tipo' = 'DEVOLUCION_HONORARIO' AND detalle->>'torneoId' = ?
                ORDER BY id DESC LIMIT 1
                """, String.class, String.valueOf(torneo))).isEqualTo(motivo);
        assertThat(honorariosService.delArbitro(juez)).doesNotContainKey(torneo);
    }

    private void designar(long torneoId, Long arbitro) {
        arbitrosService.designar(organizador, torneoId,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
    }

    private void terminar() {
        for (int i = 0; i < 4; i++) {
            jdbc.update("UPDATE inscripcion SET posicion = ? WHERE id = ?", i == 0 ? 1 : 2, inscripcion[i]);
        }
        estado("FINALIZADO");
    }

    /** Por SQL, como lo deja el resto del sistema; la sesión se vacía para que el servicio lo lea. */
    private void estado(String estado) {
        entityManager.flush();
        jdbc.update("UPDATE torneo SET estado = ? WHERE id = ?", estado, torneo);
        entityManager.clear();
    }

    private long torneo(int valor) {
        long id = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado,
                                    publicado_en)
                VALUES (?, 'Copa con árbitro', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, ?, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA', now())
                RETURNING id
                """, Long.class, organizador, valor);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "hon-" + id, id);
        return id;
    }

    private void saldo(Long usuario, String disponible) {
        jdbc.update("""
                INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, ?)
                ON CONFLICT (usuario_id) DO UPDATE SET saldo_disponible = excluded.saldo_disponible
                """, usuario, new BigDecimal(disponible));
        entityManager.clear();
    }

    private void assertBilletera(Long usuario, String disponible, String retenido) {
        entityManager.flush();
        Map<String, Object> billetera = jdbc.queryForMap(
                "SELECT saldo_disponible, saldo_retenido FROM billetera WHERE usuario_id = ?", usuario);
        assertThat((BigDecimal) billetera.get("saldo_disponible")).isEqualByComparingTo(disponible);
        assertThat((BigDecimal) billetera.get("saldo_retenido")).isEqualByComparingTo(retenido);
    }

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    /** Con la identidad verificada: email verificado, DNI y fecha de nacimiento. */
    private Long usuario(String nombre, String rol, boolean verificado) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        if (verificado) {
            persona.setTipoDoc("DNI");
            persona.setNroDoc(String.valueOf(DOCUMENTOS.incrementAndGet()));
            persona.setFechaNacimiento(LocalDate.of(1990, 1, 1));
        }
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        usuario = usuarioRepository.save(usuario);
        UsuarioRol asignacion = new UsuarioRol(usuario, rolRepository.findByNombre(rol).orElseThrow());
        asignacion.setAsignadoEn(Instant.now());
        usuarioRolRepository.save(asignacion);
        return usuario.getId();
    }
}
