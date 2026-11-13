package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.dto.CustodiaResumen;
import com.hydra.pica.plataforma_pica.billetera.dto.IngresoInscripcion;
import com.hydra.pica.plataforma_pica.billetera.dto.PanelFinanciero;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.billetera.service.PanelFinancieroService.LiquidacionFinal;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El panel financiero y la liquidación final (HU-608, PICA-342), con el escenario de la liquidación: cuatro jugadores
 * pagan $1000 más 10 % (hay $4400 en custodia), el torneo tiene $500 de gastos y premia 70 % al 1º y 30 % al 2º. Cada
 * test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class PanelFinancieroIntegracionTest {

    @Autowired private PanelFinancieroService panelFinancieroService;
    @Autowired private LiquidacionService liquidacionService;
    @Autowired private CustodiaService custodiaService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private long torneo;
    private final long[] inscripcion = new long[4];

    @BeforeEach
    void escenario() {
        organizador = usuario("org_panel");
        torneo = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado, publicado_en)
                VALUES (?, 'Copa Panel', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA', now())
                RETURNING id
                """, Long.class, organizador);
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "panel-" + torneo, torneo);
        jdbc.update("INSERT INTO premio (torneo_id, puesto, pct) VALUES (?, 1, 70), (?, 2, 30)", torneo, torneo);
        jdbc.update("INSERT INTO gasto (torneo_id, gasto_id, descripcion, monto) VALUES (?, 1, 'Cancha', 500)", torneo);
        for (int i = 0; i < 4; i++) {
            Long jugador = usuario("jug_panel_" + i);
            jdbc.update("INSERT INTO billetera (usuario_id, saldo_disponible) VALUES (?, 5000)", jugador);
            inscripcion[i] = jdbc.queryForObject("""
                    INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                    VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                    """, Long.class, torneo, jugador);
            entityManager.clear();
            inscripcionService.pagar(jugador, inscripcion[i]);
        }
        entityManager.flush();
    }

    @Test
    @DisplayName("El libro de ingresos tiene cada pago con quién pagó, sus gastos y el total, el más nuevo primero")
    void libroDeIngresos() {
        List<IngresoInscripcion> ingresos = panelFinancieroService.ingresos(organizador, torneo);

        assertThat(ingresos).hasSize(4);
        assertThat(ingresos).extracting(IngresoInscripcion::inscripcionId)
                .containsExactly(inscripcion[3], inscripcion[2], inscripcion[1], inscripcion[0]);
        IngresoInscripcion primero = ingresos.get(3);
        assertThat(primero.pagadorUsername()).isEqualTo("jug_panel_0");
        assertThat(primero.pagadorNombre()).isEqualTo("jug_panel_0 Test");
        assertThat(primero.equipoNombre()).isNull();
        assertThat(primero.monto()).isEqualByComparingTo("1000.00");
        assertThat(primero.gastosAdministrativos()).isEqualByComparingTo("100.00");
        assertThat(primero.total()).isEqualByComparingTo("1100.00");
        assertThat(primero.estado()).isEqualTo(EstadoMovimiento.CONFIRMADO);
        assertThat(primero.comprobante()).isEqualTo("I" + torneo + "-" + primero.movimientoId());
        // lo mismo que el panel: la suma de los montos es lo recaudado
        assertThat(ingresos.stream().map(IngresoInscripcion::monto).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(panelFinancieroService.delTorneo(organizador, torneo).recaudado());
    }

    @Test
    @DisplayName("El libro de ingresos de otro organizador es 404, y sin pagos es la lista vacía")
    void libroDeIngresosAjenoOVacio() {
        Long otro = usuario("otro_libro");
        assertError(() -> panelFinancieroService.ingresos(otro, torneo),
                HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);

        long vacio = jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado, publicado_en)
                VALUES (?, 'Sin pagos', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA', now())
                RETURNING id
                """, Long.class, organizador);
        assertThat(panelFinancieroService.ingresos(organizador, vacio)).isEmpty();
    }

    @Test
    @DisplayName("En curso: lo recaudado, lo comprometido en premios y el reintegro, y coincide con la custodia")
    void enCurso() {
        PanelFinanciero panel = panelFinancieroService.delTorneo(organizador, torneo);

        assertThat(panel.liquidado()).isFalse();
        assertThat(panel.recaudado()).isEqualByComparingTo("4000.00");
        assertThat(panel.gastosAdministrativos()).isEqualByComparingTo("400.00");
        assertThat(panel.gastos()).isEqualByComparingTo("500.00");
        assertThat(panel.reintegroOrganizador()).isEqualByComparingTo("500.00");
        assertThat(panel.comprometidoEnPremios()).isEqualByComparingTo("3500.00");
        assertThat(panel.aporteRifa()).isEqualByComparingTo("0");
        assertThat(panel.pagos()).isEqualTo(4);

        CustodiaResumen custodia = custodiaService.resumenDelTorneo(organizador, torneo);
        assertThat(panel.recaudado()).isEqualByComparingTo(custodia.recaudado());
        assertThat(panel.gastosAdministrativos()).isEqualByComparingTo(custodia.gastosAdministrativos());
    }

    @Test
    @DisplayName("Si los gastos superan lo recaudado, el reintegro llega hasta lo recaudado y no queda nada para premios")
    void gastosMayoresQueLoRecaudado() {
        jdbc.update("UPDATE gasto SET monto = 6000 WHERE torneo_id = ?", torneo);

        PanelFinanciero panel = panelFinancieroService.delTorneo(organizador, torneo);

        assertThat(panel.reintegroOrganizador()).isEqualByComparingTo("4000.00");
        assertThat(panel.comprometidoEnPremios()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Liquidado, el panel da lo mismo que se pagó")
    void liquidadoCoincideConLaLiquidacion() {
        terminar();
        liquidacionService.liquidarTorneo(torneo);
        entityManager.clear();

        PanelFinanciero panel = panelFinancieroService.delTorneo(organizador, torneo);
        RepartoLiquidacion reparto = panelFinancieroService.liquidacionFinal(organizador, torneo).reparto();

        assertThat(panel.liquidado()).isTrue();
        assertThat(panel.liquidadoEn()).isNotNull();
        assertThat(panel.reintegroOrganizador()).isEqualByComparingTo(suma(reparto, Rol.ORGANIZADOR));
        assertThat(panel.comprometidoEnPremios()).isEqualByComparingTo(suma(reparto, Rol.PREMIO));
        assertThat(panel.gastosAdministrativos()).isEqualByComparingTo(suma(reparto, Rol.PLATAFORMA));
        assertThat(panel.pozoAlOrganizador()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Liquidado sin nadie en un puesto con premio: no hay premios y el pozo que fue al organizador va aparte "
            + "de su reintegro, también en el archivo")
    void sinGanadores() {
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
        liquidacionService.liquidarTorneo(torneo);
        entityManager.clear();

        PanelFinanciero panel = panelFinancieroService.delTorneo(organizador, torneo);
        LiquidacionFinal liquidacion = panelFinancieroService.liquidacionFinal(organizador, torneo);
        String csv = new String(ArchivoLiquidacion.csv(liquidacion), StandardCharsets.UTF_8);

        assertThat(panel.reintegroOrganizador()).isEqualByComparingTo("500.00");
        assertThat(panel.comprometidoEnPremios()).isEqualByComparingTo("0");
        assertThat(panel.pozoAlOrganizador()).isEqualByComparingTo("3500.00");
        // la liquidación le acredita las dos cosas en un movimiento; el archivo las separa
        assertThat(liquidacion.reparto().lineas()).filteredOn(l -> l.rol() == Rol.ORGANIZADOR).hasSize(1);
        assertThat(csv).contains("Reintegro de gastos al organizador;org_panel Test (@org_panel);")
                .contains(";500,00\r\n")
                .contains("Pozo sin ganadores, al organizador;org_panel Test (@org_panel);")
                .contains(";3500,00\r\n")
                .contains("Total repartido;;;4400,00")
                .doesNotContain("Premio del");
    }

    @Test
    @DisplayName("Sin gastos ni ganadores, el archivo no muestra un reintegro en cero: todo es pozo sin ganadores")
    void sinGastosNiGanadores() {
        jdbc.update("DELETE FROM gasto WHERE torneo_id = ?", torneo);
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
        liquidacionService.liquidarTorneo(torneo);
        entityManager.clear();

        PanelFinanciero panel = panelFinancieroService.delTorneo(organizador, torneo);
        String csv = new String(ArchivoLiquidacion.csv(panelFinancieroService.liquidacionFinal(organizador, torneo)),
                StandardCharsets.UTF_8);

        assertThat(panel.reintegroOrganizador()).isEqualByComparingTo("0");
        assertThat(panel.pozoAlOrganizador()).isEqualByComparingTo("4000.00");
        assertThat(csv).doesNotContain("Reintegro de gastos")
                .contains("Pozo sin ganadores, al organizador;org_panel Test (@org_panel);");
    }

    @Test
    @DisplayName("Antes de liquidar, la descarga es 409 FONDOS_EN_CUSTODIA")
    void descargaAntesDeLiquidar() {
        assertError(() -> panelFinancieroService.liquidacionFinal(organizador, torneo),
                HttpStatus.CONFLICT, CodigoError.FONDOS_EN_CUSTODIA);
        terminar();
        assertError(() -> panelFinancieroService.liquidacionFinal(organizador, torneo),
                HttpStatus.CONFLICT, CodigoError.FONDOS_EN_CUSTODIA);
    }

    @Test
    @DisplayName("El CSV tiene cada concepto y el total cierra con lo repartido")
    void csv() {
        LiquidacionFinal liquidacion = liquidar();

        String csv = new String(ArchivoLiquidacion.csv(liquidacion), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿Liquidación final;Copa Panel");
        assertThat(csv).contains("Concepto;Beneficiario;Acreditado;Monto")
                .contains("Premio del 1º puesto;jug_panel_0 Test (@jug_panel_0);")
                .contains("Premio del 2º puesto;jug_panel_1 Test (@jug_panel_1);")
                .contains("Reintegro de gastos al organizador;org_panel Test (@org_panel);")
                .contains("Gastos administrativos (plataforma);Plataforma;")
                .contains("Total repartido;;;4400,00");
        BigDecimal sumaDeLasFilas = Arrays.stream(csv.split("\r\n"))
                .filter(fila -> fila.startsWith("Premio") || fila.startsWith("Reintegro") || fila.startsWith("Gastos"))
                .map(fila -> new BigDecimal(fila.substring(fila.lastIndexOf(';') + 1).replace(',', '.')))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumaDeLasFilas).isEqualByComparingTo("4400.00");
    }

    @Test
    @DisplayName("El PDF es un PDF con cada concepto y el total")
    void pdf() throws Exception {
        LiquidacionFinal liquidacion = liquidar();

        byte[] pdf = ArchivoLiquidacion.pdf(liquidacion);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String texto = new PdfTextExtractor(new PdfReader(pdf)).getTextFromPage(1);
        assertThat(texto).contains("Liquidación final: Copa Panel")
                .contains("Premio del 1º puesto")
                .contains("Reintegro de gastos al organizador")
                .contains("Gastos administrativos (plataforma)")
                .contains("Total repartido")
                .contains("$ 4.400,00");
    }

    @Test
    @DisplayName("Otro organizador no lo ve: 404")
    void otroOrganizador() {
        Long otro = usuario("otro_panel");

        assertError(() -> panelFinancieroService.delTorneo(otro, torneo),
                HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
        assertError(() -> panelFinancieroService.liquidacionFinal(otro, torneo),
                HttpStatus.NOT_FOUND, CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    @WithMockUser(authorities = "TORNEO_ADMINISTRAR")
    @DisplayName("Con TORNEO_ADMINISTRAR se ve el de cualquier organizador")
    void admin() {
        Long admin = usuario("admin_panel");

        assertThat(panelFinancieroService.delTorneo(admin, torneo).recaudado()).isEqualByComparingTo("4000.00");
    }

    private LiquidacionFinal liquidar() {
        terminar();
        liquidacionService.liquidarTorneo(torneo);
        entityManager.clear();
        return panelFinancieroService.liquidacionFinal(organizador, torneo);
    }

    /** El 1º y el 2º, los otros dos sin premio; el torneo queda FINALIZADO. */
    private void terminar() {
        int[] posiciones = {1, 2, 3, 3};
        for (int i = 0; i < posiciones.length; i++) {
            jdbc.update("UPDATE inscripcion SET posicion = ? WHERE id = ?", posiciones[i], inscripcion[i]);
        }
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
    }

    private static BigDecimal suma(RepartoLiquidacion reparto, Rol rol) {
        return reparto.lineas().stream().filter(l -> l.rol() == rol).map(RepartoLiquidacion.Linea::monto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
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
