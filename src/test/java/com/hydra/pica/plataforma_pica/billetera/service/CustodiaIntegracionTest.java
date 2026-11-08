package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCustodia;
import com.hydra.pica.plataforma_pica.billetera.dto.ComprobantePago;
import com.hydra.pica.plataforma_pica.billetera.dto.CustodiaResumen;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
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
 * La custodia por evento (HU-603, PICA-321): el 100 % de cada pago queda retenido en la de su torneo o su rifa, nadie
 * lo mueve antes del cierre (tampoco el organizador) y quien pagó ve su comprobante. El torneo cobra $1000 más 10 % y
 * la rifa $500 por número más 10 %. Cada test hace rollback al terminar.
 */
@SpringBootTest(properties = "app.cobro.modo=saldo")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class CustodiaIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);

    @Autowired private CustodiaService custodiaService;
    @Autowired private ComprobanteService comprobanteService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long jugador;
    private Long torneo;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_custodia");
        jugador = usuario("jug_custodia");
        saldo(jugador, "5000.00");
        torneo = torneoConGastos();
    }

    // --- 1. el 100 % de cada pago queda en la custodia de su evento ---------------------------------

    @Test
    void laInscripcionEntraEnteraALaCustodiaDelTorneoYQuedaEnLaAuditoria() {
        inscripcionService.pagar(jugador, pendienteDePago(jugador));

        CustodiaResumen custodia = custodiaService.resumenDelTorneo(organizador, torneo);
        assertThat(custodia.estado()).isEqualTo(EstadoCustodia.RETENIDA);
        assertThat(custodia.total()).isEqualByComparingTo("1100.00");
        assertThat(custodia.recaudado()).isEqualByComparingTo("1000.00");
        assertThat(custodia.gastosAdministrativos()).isEqualByComparingTo("100.00");
        assertThat(custodia.pagos()).isEqualTo(1);
        assertThat(custodia.eventoTerminado()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auditoria a JOIN custodia c ON c.id::text = a.entidad_id
                WHERE a.entidad = 'CUSTODIA' AND c.torneo_id = ? AND a.usuario_id = ?
                """, Integer.class, torneo, jugador)).isEqualTo(2);
    }

    @Test
    void laCompraEntraALaCustodiaDeLaRifaYNoALaDeSuTorneo() {
        long rifa = rifaEnVenta(torneo);
        ventaRifaService.reservar(jugador, rifa, List.of(5, 6));
        ventaRifaService.comprar(jugador, rifa, List.of(5, 6), "compra-custodia-1");

        CustodiaResumen deLaRifa = custodiaService.resumenDeLaRifa(organizador, rifa);
        assertThat(deLaRifa.total()).isEqualByComparingTo("1100.00");
        assertThat(deLaRifa.gastosAdministrativos()).isEqualByComparingTo("100.00");
        assertThat(custodiaService.resumenDelTorneo(organizador, torneo).total()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void sinPagosLaCustodiaEstaVacia() {
        CustodiaResumen custodia = custodiaService.resumenDelTorneo(organizador, torneo);

        assertThat(custodia.estado()).isEqualTo(EstadoCustodia.RETENIDA);
        assertThat(custodia.total()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void soloElOrganizadorVeLaCustodia() {
        assertError(() -> custodiaService.resumenDelTorneo(jugador, torneo), HttpStatus.NOT_FOUND,
                CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- 2. nadie la mueve antes del cierre ---------------------------------------------------------

    @Test
    void conElTorneoEnCursoLaPuertaDeSalidaFallaConFondosEnCustodia() {
        inscripcionService.pagar(jugador, pendienteDePago(jugador));
        jdbc.update("UPDATE torneo SET estado = 'EN_CURSO' WHERE id = ?", torneo);
        entityManager.clear();

        assertError(() -> custodiaService.liberarTorneo(torneo), HttpStatus.CONFLICT, CodigoError.FONDOS_EN_CUSTODIA);
        assertThat(custodiaService.resumenDelTorneo(organizador, torneo).estado()).isEqualTo(EstadoCustodia.RETENIDA);
        assertBilletera(jugador, "3900.00", "1100.00");
    }

    @Test
    void elOrganizadorNoRetiraLosFondosDeSuTorneoAntesDelCierre() {
        inscripcionService.pagar(jugador, pendienteDePago(jugador));

        assertError(() -> custodiaService.retirarDelTorneo(organizador, torneo), HttpStatus.CONFLICT,
                CodigoError.FONDOS_EN_CUSTODIA);
        assertThat(custodiaService.resumenDelTorneo(organizador, torneo).total()).isEqualByComparingTo("1100.00");
    }

    @Test
    void niDespuesDelCierreElOrganizadorLosSacaDirecto() {
        inscripcionService.pagar(jugador, pendienteDePago(jugador));
        finalizar();

        assertError(() -> custodiaService.retirarDelTorneo(organizador, torneo), HttpStatus.CONFLICT,
                CodigoError.FONDOS_EN_CUSTODIA);
        assertThat(custodiaService.resumenDelTorneo(organizador, torneo).eventoTerminado()).isTrue();
    }

    @Test
    void laRifaEnVentaTampocoSeLibera() {
        long rifa = rifaEnVenta(null);
        ventaRifaService.reservar(jugador, rifa, List.of(1));
        ventaRifaService.comprar(jugador, rifa, List.of(1), "compra-custodia-1");

        assertError(() -> custodiaService.liberarRifa(rifa), HttpStatus.CONFLICT, CodigoError.FONDOS_EN_CUSTODIA);
        assertError(() -> custodiaService.retirarDeLaRifa(organizador, rifa), HttpStatus.CONFLICT,
                CodigoError.FONDOS_EN_CUSTODIA);
    }

    // --- la puerta de salida, después del cierre (la usa HU-606) -----------------------------------

    @Test
    void conElTorneoFinalizadoSeLiberaUnaSolaVezYSaleDelRetenidoDelPagador() {
        inscripcionService.pagar(jugador, pendienteDePago(jugador));
        finalizar();

        LiberacionCustodia liberacion = custodiaService.liberarTorneo(torneo);

        assertThat(liberacion.estado()).isEqualTo(EstadoCustodia.LIBERADA);
        assertThat(liberacion.total()).isEqualByComparingTo("1100.00");
        assertThat(liberacion.gastosAdministrativos()).isEqualByComparingTo("100.00");
        assertBilletera(jugador, "3900.00", "0");
        assertError(() -> custodiaService.liberarTorneo(torneo), HttpStatus.CONFLICT, CodigoError.CUSTODIA_LIBERADA);
        assertError(() -> custodiaService.retirarDelTorneo(organizador, torneo), HttpStatus.CONFLICT,
                CodigoError.CUSTODIA_LIBERADA);
    }

    @Test
    void conElTorneoCanceladoQuedaParaReintegrar() {
        inscripcionService.pagar(jugador, pendienteDePago(jugador));
        jdbc.update("UPDATE torneo SET estado = 'CANCELADO', motivo_cancelacion = 'Lluvia' WHERE id = ?", torneo);
        entityManager.clear();

        assertThat(custodiaService.liberarTorneo(torneo).estado()).isEqualTo(EstadoCustodia.REINTEGRADA);
    }

    @Test
    void laRifaSeLiberaAlVencerElPlazoDeReclamo() {
        long rifa = rifaEnVenta(null);
        ventaRifaService.reservar(jugador, rifa, List.of(1));
        ventaRifaService.comprar(jugador, rifa, List.of(1), "compra-custodia-1");
        jdbc.update("UPDATE rifa SET estado = 'SORTEADA', semilla_obtenida_en = now() - interval '1 hour' WHERE id = ?", rifa);
        entityManager.clear();
        assertError(() -> custodiaService.liberarRifa(rifa), HttpStatus.CONFLICT, CodigoError.FONDOS_EN_CUSTODIA);

        jdbc.update("UPDATE rifa SET semilla_obtenida_en = now() - interval '49 hours' WHERE id = ?", rifa);
        entityManager.clear();

        LiberacionCustodia liberacion = custodiaService.liberarRifa(rifa);
        assertThat(liberacion.total()).isEqualByComparingTo("550.00");
        assertBilletera(jugador, "4450.00", "0");
    }

    @Test
    void unEventoTerminadoSinPagosSeLiberaVacio() {
        finalizar();

        LiberacionCustodia liberacion = custodiaService.liberarTorneo(torneo);

        assertThat(liberacion.custodiaId()).isNull();
        assertThat(liberacion.total()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // --- 3. el comprobante --------------------------------------------------------------------------

    @Test
    void quienPagoVeSuComprobante() {
        long inscripcion = pendienteDePago(jugador);
        inscripcionService.pagar(jugador, inscripcion);

        ComprobantePago comprobante = comprobanteService.deInscripcion(jugador, inscripcion);

        assertThat(comprobante.numero()).startsWith("I" + torneo + "-");
        assertThat(comprobante.pagadoEn()).isNotNull();
        assertThat(comprobante.pagadorNombre()).isEqualTo("jug_custodia Test");
        assertThat(comprobante.pagadorUsername()).isEqualTo("jug_custodia");
        assertThat(comprobante.concepto()).isEqualTo("Inscripción al torneo Torneo con custodia");
        assertThat(comprobante.monto()).isEqualByComparingTo("1000.00");
        assertThat(comprobante.gastosAdministrativos()).isEqualByComparingTo("100.00");
        assertThat(comprobante.total()).isEqualByComparingTo("1100.00");
        assertThat(comprobante.moneda()).isEqualTo("ARS");
        assertThat(comprobante.custodia()).isEqualTo(EstadoCustodia.RETENIDA);
    }

    @Test
    void elComprobanteDeOtroNoExiste() {
        long inscripcion = pendienteDePago(jugador);
        inscripcionService.pagar(jugador, inscripcion);

        assertError(() -> comprobanteService.deInscripcion(organizador, inscripcion), HttpStatus.NOT_FOUND,
                CodigoError.INSCRIPCION_NO_ENCONTRADA);
    }

    @Test
    void sinPagoNoHayComprobante() {
        long inscripcion = pendienteDePago(jugador);

        assertError(() -> comprobanteService.deInscripcion(jugador, inscripcion), HttpStatus.NOT_FOUND,
                CodigoError.COMPROBANTE_NO_ENCONTRADO);
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private void finalizar() {
        jdbc.update("UPDATE torneo SET estado = 'FINALIZADO' WHERE id = ?", torneo);
        entityManager.clear();
    }

    private static void assertError(ThrowingCallable accion, HttpStatus status, CodigoError codigo) {
        assertThatThrownBy(accion).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
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

    private long torneoConGastos() {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_gastos_administrativos, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo con custodia', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL',
                        8, 2, 1000, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
    }

    private long pendienteDePago(long usuario) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, 'PENDIENTE_PAGO', now()) RETURNING id
                """, Long.class, torneo, usuario);
    }

    /** Del 1 al 100 a $500, con la venta abierta desde hace una hora; con torneo, si se pasa. */
    private long rifaEnVenta(Long torneoId) {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa con custodia", null, 1, 100,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS), null, null));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(new PremioRifaRequest(new BigDecimal("100"), null)));
        rifaService.publicar(organizador, creada.id());
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour', torneo_id = ? WHERE id = ?", torneoId,
                creada.id());
        entityManager.clear();
        return creada.id();
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
