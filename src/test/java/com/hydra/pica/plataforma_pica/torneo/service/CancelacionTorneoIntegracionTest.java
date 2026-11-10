package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.almacenamiento.AlmacenArchivos;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.torneo.dto.CancelacionRealizada;
import com.hydra.pica.plataforma_pica.torneo.dto.GastoDeclarado;
import com.hydra.pica.plataforma_pica.torneo.dto.SolicitudCancelacion;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * La cancelación de un torneo (HU-605, PICA-347). Tres jugadores pagan con saldo $1000 más 10 % de gastos
 * administrativos: hay $3300 en custodia y el pozo es $3000. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class CancelacionTorneoIntegracionTest {

    @Autowired private CancelacionTorneoService cancelacionTorneoService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private AlmacenArchivos almacen;

    private static final byte[] PDF = "%PDF-1.7\nfactura".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2};

    private Long organizador;
    private long torneo;
    private final Long[] jugador = new Long[3];
    private final long[] inscripcion = new long[3];

    @BeforeEach
    void escenario() {
        organizador = usuario("org_can");
        torneo = torneo();
        for (int i = 0; i < 3; i++) {
            jugador[i] = usuario("jug_can_" + i);
            saldo(jugador[i], "5000.00");
            inscripcion[i] = inscripcion(jugador[i], "PENDIENTE_PAGO");
            inscripcionService.pagar(jugador[i], inscripcion[i]);
        }
        entityManager.flush();
    }

    // --- sin gastos: se aprueba sola ----------------------------------------------------------------

    @Test
    void sinGastosElTorneoQuedaCanceladoYCadaUnoRecuperaTodoLoQuePago() {
        CancelacionRealizada cancelacion = cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Se inundó la cancha", List.of()));

        assertThat(cancelacion.estado()).isEqualTo("AUTOMATICA");
        assertThat(cancelacion.reintegrado()).isEqualByComparingTo("3300.00");
        for (Long j : jugador) {
            assertBilletera(j, "5000.00", "0.00");
        }
        assertThat(jdbc.queryForMap("SELECT estado, motivo_cancelacion FROM torneo WHERE id = ?", torneo))
                .containsEntry("estado", "CANCELADO").containsEntry("motivo_cancelacion", "Se inundó la cancha");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo))
                .isEqualTo("REINTEGRADA");
    }

    @Test
    void lasConfirmadasQuedanReintegradasYLasQueNoTerminaronDePagarCanceladas() {
        Long sinPagar = usuario("jug_can_sin_pagar");
        long pendiente = inscripcion(sinPagar, "PENDIENTE_PAGO");
        long borrador = inscripcion(usuario("jug_can_borrador"), "BORRADOR");

        cancelar();

        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE id IN (?, ?, ?)", String.class,
                inscripcion[0], inscripcion[1], inscripcion[2])).containsOnly("REINTEGRADA");
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE id IN (?, ?)", String.class,
                pendiente, borrador)).containsOnly("CANCELADA");
    }

    @Test
    void cadaPagadorRecibeUnReintegroDelTorneoQueNoEsGanancia() {
        cancelar();

        Map<String, Object> reintegro = jdbc.queryForMap("""
                SELECT m.tipo, m.monto, m.torneo_id, m.estado, m.descripcion, b.ganancia_acumulada
                FROM movimiento m JOIN billetera b ON b.id = m.billetera_id
                WHERE b.usuario_id = ? AND m.tipo = 'REINTEGRO'
                """, jugador[0]);
        assertThat(reintegro).containsEntry("monto", new BigDecimal("1100.00"))
                .containsEntry("torneo_id", torneo)
                .containsEntry("estado", "CONFIRMADO")
                .containsEntry("descripcion", "Reintegro por la cancelación del torneo Torneo a cancelar");
        assertThat((BigDecimal) reintegro.get("ganancia_acumulada")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("""
                SELECT sum(m.monto) FROM movimiento m WHERE m.torneo_id = ? AND m.tipo = 'REINTEGRO'
                """, BigDecimal.class, torneo)).isEqualByComparingTo("3300.00");
    }

    @Test
    void enEquiposElReintegroVuelveALaBilleteraQuePago() {
        Long capitan = usuario("cap_can");
        saldo(capitan, "2000.00");
        long equipo = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, 'Los de siempre') "
                + "RETURNING id", Long.class, capitan);
        long porEquipos = torneo();
        jdbc.update("UPDATE torneo SET tipo_participante = 'EQUIPO', tamanio_equipo = 1, nomina_maxima = 2 "
                + "WHERE id = ?", porEquipos);
        long deEquipo = jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, porEquipos, equipo);
        jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?)", capitan, equipo);
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?)", deEquipo, capitan,
                equipo);
        inscripcionService.pagar(capitan, deEquipo);
        entityManager.flush();
        assertBilletera(capitan, "900.00", "1100.00");

        cancelacionTorneoService.cancelar(organizador, porEquipos, new SolicitudCancelacion("Lluvia", List.of()));

        assertBilletera(capitan, "2000.00", "0.00");
    }

    @Test
    void unTorneoSinPagosSeCancelaIgual() {
        long gratis = torneo();

        CancelacionRealizada cancelacion = cancelacionTorneoService.cancelar(organizador, gratis,
                new SolicitudCancelacion("Lluvia", List.of()));

        assertThat(cancelacion.reintegrado()).isEqualByComparingTo("0");
        assertThat(cancelacion.pozo()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, gratis))
                .isEqualTo("CANCELADO");
    }

    @Test
    void laRespuestaTraeElPozoYLaCancelacionQuedaRegistradaYAuditada() {
        CancelacionRealizada cancelacion = cancelar();

        assertThat(cancelacion.pozo()).isEqualByComparingTo("3000.00");
        assertThat(cancelacion.totalGastos()).isEqualByComparingTo("0");
        assertThat(cancelacion.torneoId()).isEqualTo(torneo);
        assertThat(jdbc.queryForObject("SELECT estado FROM cancelacion WHERE id = ?", String.class,
                cancelacion.cancelacionId())).isEqualTo("AUTOMATICA");
        assertThat(jdbc.queryForList("""
                SELECT accion FROM auditoria WHERE entidad = 'TORNEO' AND entidad_id = ? AND accion IN
                ('CANCELACION', 'REINTEGRO') ORDER BY id
                """, String.class, String.valueOf(torneo)))
                .containsExactly("CANCELACION", "REINTEGRO", "REINTEGRO", "REINTEGRO");
        assertThat(jdbc.queryForObject("""
                SELECT usuario_id FROM auditoria WHERE entidad = 'TORNEO' AND entidad_id = ? AND accion = 'CANCELACION'
                """, Long.class, String.valueOf(torneo))).isEqualTo(organizador);
    }

    // --- con gastos: queda en revisión -------------------------------------------------------------

    @Test
    void conGastosQuedaEnRevisionYLaPlataSigueEnCustodia() {
        CancelacionRealizada cancelacion = cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Se inundó la cancha", List.of(
                        gasto("300.00", "Alquiler de la cancha", PDF), gasto("150.50", "Árbitro", JPG))));

        assertThat(cancelacion.estado()).isEqualTo("EN_REVISION");
        assertThat(cancelacion.totalGastos()).isEqualByComparingTo("450.50");
        assertThat(cancelacion.pozo()).isEqualByComparingTo("3000.00");
        assertThat(cancelacion.reintegrado()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForMap("SELECT estado, motivo_cancelacion FROM torneo WHERE id = ?", torneo))
                .containsEntry("estado", "CANCELACION_EN_REVISION")
                .containsEntry("motivo_cancelacion", "Se inundó la cancha");
        assertThat(jdbc.queryForObject("SELECT estado FROM custodia WHERE torneo_id = ?", String.class, torneo))
                .isEqualTo("RETENIDA");
        assertBilletera(jugador[0], "3900.00", "1100.00");
        assertThat(jdbc.queryForList("SELECT estado FROM inscripcion WHERE torneo_id = ?", String.class, torneo))
                .containsOnly("CONFIRMADA");
        assertThat(jdbc.queryForObject("SELECT estado FROM cancelacion WHERE id = ?", String.class,
                cancelacion.cancelacionId())).isEqualTo("EN_REVISION");
    }

    @Test
    void losGastosQuedanGuardadosConSuComprobante() {
        CancelacionRealizada cancelacion = cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Lluvia", List.of(gasto("300.00", "Alquiler de la cancha", PDF),
                        gasto("80.00", "Trofeos", PNG))));

        List<Map<String, Object>> gastos = jdbc.queryForList("""
                SELECT monto, concepto, comprobante, tipo_contenido FROM gasto_cancelacion
                WHERE cancelacion_id = ? ORDER BY id
                """, cancelacion.cancelacionId());
        assertThat(gastos).hasSize(2);
        assertThat(gastos.get(0)).containsEntry("concepto", "Alquiler de la cancha")
                .containsEntry("tipo_contenido", "application/pdf");
        assertThat((BigDecimal) gastos.get(0).get("monto")).isEqualByComparingTo("300.00");
        assertThat(gastos.get(1)).containsEntry("tipo_contenido", "image/png");
        assertThat(almacen.leer((String) gastos.get(0).get("comprobante"))).hasValue(PDF);
        assertThat(almacen.leer((String) gastos.get(1).get("comprobante"))).hasValue(PNG);
    }

    @Test
    void unGastoSinComprobanteNoSeAcepta() {
        assertCampoInvalido(List.of(gasto("300.00", "Cancha", PDF), gasto("100.00", "Árbitro", null)),
                "gastos[1].comprobante", ErrorCampo.Codigo.REQUERIDO,
                "Debe adjuntar un comprobante válido por cada gasto declarado");
    }

    @Test
    void unComprobanteQueNoEsPdfNiImagenNoSeAcepta() {
        assertCampoInvalido(List.of(gasto("300.00", "Cancha", "hola".getBytes())), "gastos[0].comprobante",
                ErrorCampo.Codigo.FORMATO_INVALIDO, "Tiene que ser un PDF, JPG o PNG");
    }

    @Test
    void unComprobanteDeMasDe5MbNoSeAcepta() {
        byte[] grande = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, grande, 0, PDF.length);
        assertCampoInvalido(List.of(gasto("300.00", "Cancha", grande)), "gastos[0].comprobante",
                ErrorCampo.Codigo.LONGITUD, "Hasta 5 MB");
    }

    @Test
    void masDeDiezGastosNoSeAceptan() {
        List<GastoDeclarado> once = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> gasto("10.00", "Gasto " + i, PDF)).toList();
        assertCampoInvalido(once, "gastos", ErrorCampo.Codigo.LONGITUD, "Hasta 10 gastos");
    }

    @Test
    void elMontoTieneQueSerPositivoConDosDecimalesYElConceptoObligatorio() {
        assertCampoInvalido(List.of(gasto("0", "Cancha", PDF)), "gastos[0].monto",
                ErrorCampo.Codigo.VALOR_INVALIDO, "Tiene que ser mayor a 0, con hasta dos decimales");
        assertCampoInvalido(List.of(gasto("10.555", "Cancha", PDF)), "gastos[0].monto",
                ErrorCampo.Codigo.VALOR_INVALIDO, "Tiene que ser mayor a 0, con hasta dos decimales");
        assertCampoInvalido(List.of(gasto("10.00", " ", PDF)), "gastos[0].concepto",
                ErrorCampo.Codigo.REQUERIDO, "Indicá el concepto del gasto");
    }

    @Test
    void unaCancelacionEnRevisionNoSePuedeVolverAEnviar() {
        cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Lluvia", List.of(gasto("300.00", "Cancha", PDF))));
        entityManager.flush();
        entityManager.clear();

        assertError(this::cancelar, HttpStatus.CONFLICT, CodigoError.TORNEO_NO_CANCELABLE);
        assertBilletera(jugador[0], "3900.00", "1100.00");
    }

    // --- tope de gastos -----------------------------------------------------------------------------

    @Test
    void losGastosNoPuedenPasarElTopeDelPozo() {
        assertThatThrownBy(() -> cancelacionTorneoService.cancelar(organizador, torneo, new SolicitudCancelacion(
                "Lluvia", List.of(gasto("400.00", "Cancha", PDF), gasto("200.01", "Árbitro", PNG)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.GASTOS_SOBRE_TOPE);
                    assertThat(e.getMessage()).isEqualTo("Los gastos a retener no pueden superar el 20% del pozo. "
                            + "El monto excedente debe ser devuelto a los participantes");
                    assertThat(e.getPropiedades()).containsEntry("pctMaximo", new BigDecimal("20.00"))
                            .containsEntry("pozo", new BigDecimal("3000.00"))
                            .containsEntry("maximo", new BigDecimal("600.00"))
                            .containsEntry("declarado", new BigDecimal("600.01"));
                });
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_ABIERTA");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cancelacion WHERE torneo_id = ?", Long.class, torneo))
                .isZero();
    }

    @Test
    void justoEnElTopeSeAcepta() {
        CancelacionRealizada cancelacion = cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Lluvia", List.of(gasto("600.00", "Cancha", PDF))));

        assertThat(cancelacion.estado()).isEqualTo("EN_REVISION");
    }

    @Test
    void elTopeSaleDelParametroDeLaPlataforma() {
        jdbc.update("UPDATE parametro_plataforma SET valor = 10 WHERE clave = 'CANCELACION_GASTOS_TOPE'");
        entityManager.clear();

        assertThatThrownBy(() -> cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Lluvia", List.of(gasto("300.01", "Cancha", PDF)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.GASTOS_SOBRE_TOPE);
                    assertThat(e.getMessage()).startsWith("Los gastos a retener no pueden superar el 10% del pozo.");
                    assertThat(e.getPropiedades()).containsEntry("maximo", new BigDecimal("300.00"));
                });
    }

    @Test
    void sinPagosNoSePuedeRetenerNada() {
        long gratis = torneo();

        assertError(() -> cancelacionTorneoService.cancelar(organizador, gratis,
                        new SolicitudCancelacion("Lluvia", List.of(gasto("1.00", "Cancha", PDF)))),
                HttpStatus.BAD_REQUEST, CodigoError.GASTOS_SOBRE_TOPE);
    }

    // --- quién y cuándo ------------------------------------------------------------------------------

    @Test
    void otroUsuarioNoEncuentraElTorneo() {
        assertError(() -> cancelacionTorneoService.cancelar(jugador[0], torneo,
                new SolicitudCancelacion("Lluvia", List.of())), HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
        assertBilletera(jugador[0], "3900.00", "1100.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSCRIPCION_CERRADA", "EN_CURSO"})
    void seCancelaConLaInscripcionCerradaOConElTorneoEnCurso(String estado) {
        jdbc.update("UPDATE torneo SET estado = ? WHERE id = ?", estado, torneo);
        entityManager.clear();

        assertThat(cancelar().estado()).isEqualTo("AUTOMATICA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BORRADOR", "FINALIZADO"})
    void noSeCancelaEnBorradorNiFinalizado(String estado) {
        jdbc.update("UPDATE torneo SET estado = ? WHERE id = ?", estado, torneo);
        entityManager.clear();

        assertError(this::cancelar, HttpStatus.CONFLICT, CodigoError.TORNEO_NO_CANCELABLE);
        assertBilletera(jugador[0], "3900.00", "1100.00");
    }

    @Test
    void unaSegundaCancelacionNoPagaDosVeces() {
        cancelar();
        entityManager.flush();
        entityManager.clear();

        assertError(this::cancelar, HttpStatus.CONFLICT, CodigoError.TORNEO_NO_CANCELABLE);
        assertBilletera(jugador[0], "5000.00", "0.00");
    }

    @Test
    void sinMotivoNoSeCancela() {
        assertError(() -> cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("  ", List.of())), HttpStatus.BAD_REQUEST, CodigoError.VALIDACION);
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_ABIERTA");
    }

    // --- escenario ----------------------------------------------------------------------------------

    private static GastoDeclarado gasto(String monto, String concepto, byte[] comprobante) {
        return new GastoDeclarado(new BigDecimal(monto), concepto, comprobante);
    }

    /** Nada cambia: el torneo sigue abierto y la plata, en custodia. */
    private void assertCampoInvalido(List<GastoDeclarado> gastos, String campo, ErrorCampo.Codigo codigo,
                                     String mensaje) {
        assertThatThrownBy(() -> cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Lluvia", gastos))).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCodigo()).isEqualTo(CodigoError.VALIDACION);
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getPropiedades().get("errores")).asInstanceOf(InstanceOfAssertFactories.LIST)
                    .contains(new ErrorCampo(campo, codigo, mensaje));
        });
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("INSCRIPCION_ABIERTA");
    }

    private CancelacionRealizada cancelar() {
        return cancelacionTorneoService.cancelar(organizador, torneo,
                new SolicitudCancelacion("Se inundó la cancha", List.of()));
    }

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    private long torneo() {
        long id = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado,
                                    publicado_en)
                VALUES (?, 'Torneo a cancelar', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA',
                        now())
                RETURNING id
                """, Long.class, organizador);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "can-" + id, id);
        return id;
    }

    private long inscripcion(Long usuario, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, now()) RETURNING id
                """, Long.class, torneo, usuario, estado);
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

    private Long usuario(String nombre) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(nombre);
        usuario.setEmail(nombre + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario).getId();
    }
}
