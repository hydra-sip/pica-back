package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
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
 * El cierre de la venta y el sellado de la lista (PICA-261) contra Postgres, con las pruebas de aceptación de HU-407,
 * y el compromiso del sorteo que se publica con el sello (PICA-263), con las de HU-502. La tarea programada está
 * apagada en los tests: se la llama a mano. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class CierreVentaIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant SORTEO = EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS);

    @Autowired private CierreVentaService cierreVentaService;
    @Autowired private CierreAutomaticoRifa cierreAutomaticoRifa;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
    @Autowired private RifaPublicaService rifaPublicaService;
    @Autowired private SemillaRifaService semillaRifaService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long comprador;
    private Long otro;
    private Long rifa;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_cierre").getId();
        comprador = usuario("comprador_cierre").getId();
        otro = usuario("otro_cierre").getId();
        rifa = enVenta();
    }

    // --- pruebas de aceptación de HU-407 --------------------------------------------------------------

    @Test
    void despuesDeLaVentanaDeVentaNoSePuedeVender() {
        ventaRifaService.reservar(comprador, rifa, List.of(3));
        terminarLaVenta(rifa);

        assertThatConflicto(() -> ventaRifaService.comprar(comprador, rifa, List.of(3), "compra-tarde"),
                CodigoError.VENTA_CERRADA);
        assertThatConflicto(() -> ventaRifaService.reservar(otro, rifa, List.of(4)), CodigoError.VENTA_CERRADA);
    }

    @Test
    void alLlegarLaFechaDeCierreLaVentaSeCierraSola() {
        VentaRifaResultado primera = comprar(comprador, List.of(12));
        VentaRifaResultado segunda = comprar(otro, List.of(5));
        ventaRifaService.reservar(comprador, rifa, List.of(40));
        Long sigueEnVenta = enVenta();
        terminarLaVenta(rifa);

        assertThat(cierreAutomaticoRifa.cerrarVencidas()).isEqualTo(1);

        entityManager.clear();
        RifaDetalle cerrada = rifaService.detalle(organizador, rifa);
        assertThat(cerrada.estado()).isEqualTo(EstadoRifa.CERRADA);
        // una línea por vendido, de menor a mayor: número;código del ticket
        assertThat(cerrada.listaHash()).isEqualTo(SelloTorneo.sha256(
                "5;" + segunda.ticketCodigo() + "\n12;" + primera.ticketCodigo()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM numero WHERE rifa_id = ? AND estado = 'RESERVADO'",
                Long.class, rifa)).isZero();
        assertThat(rifaService.detalle(organizador, sigueEnVenta).estado()).isEqualTo(EstadoRifa.EN_VENTA);
        assertThat(auditoria(rifa)).isEqualTo("FECHA");
    }

    @Test
    void conLaListaSelladaNoSePuedeModificar() {
        comprar(comprador, List.of(7));
        RifaDetalle cerrada = cierreVentaService.cerrar(organizador, rifa);

        assertThatConflicto(() -> ventaRifaService.reservar(otro, rifa, List.of(8)), CodigoError.LISTA_SELLADA);
        assertThatConflicto(() -> ventaRifaService.comprar(otro, rifa, List.of(8), "compra-sellada"),
                CodigoError.LISTA_SELLADA);
        assertThatConflicto(() -> rifaService.volverABorrador(organizador, rifa), CodigoError.LISTA_SELLADA);
        assertThatConflicto(() -> rifaService.eliminar(organizador, rifa), CodigoError.LISTA_SELLADA);
        assertThatConflicto(() -> cierreVentaService.cerrar(organizador, rifa), CodigoError.LISTA_SELLADA);
        assertThat(rifaService.detalle(organizador, rifa).listaHash()).isEqualTo(cerrada.listaHash());
    }

    @Test
    void alCerrarLaVentaQuedaRegistradaLaFechaDelSellado() {
        comprar(comprador, List.of(1, 2));

        RifaDetalle cerrada = cierreVentaService.cerrar(organizador, rifa);

        assertThat(cerrada.estado()).isEqualTo(EstadoRifa.CERRADA);
        assertThat(cerrada.listaSelladaEn()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(cerrada.listaHash()).hasSize(64);
        assertThat(auditoria(rifa)).isEqualTo("MANUAL");
    }

    // --- pruebas de aceptación de HU-502 --------------------------------------------------------------

    @Test
    void sinElCompromisoPublicadoNoSeObtieneLaSemilla() {
        assertThatConflicto(() -> semillaRifaService.obtener(rifa), CodigoError.COMPROMISO_NO_PUBLICADO);
    }

    @Test
    void elCompromisoQuedaPublicadoConSuFecha() {
        comprar(comprador, List.of(9));
        cierreVentaService.cerrar(organizador, rifa);
        entityManager.flush();
        entityManager.clear();

        RifaPublicaDetalle publica = rifaPublicaService.detalle(rifa);
        assertThat(publica.compromisoHash())
                .isEqualTo(SelloTorneo.sha256(publica.listaHash() + ":" + publica.fechaSorteo()));
        assertThat(publica.compromisoPublicadoEn()).isEqualTo(publica.listaSelladaEn());
    }

    @Test
    void elCompromisoSePublicaAntesDeObtenerLaSemilla() {
        comprar(comprador, List.of(9));
        cierreVentaService.cerrar(organizador, rifa);

        Rifa conSemilla = semillaRifaService.obtener(rifa);

        assertThat(conSemilla.getSemilla()).hasSize(64);
        assertThat(conSemilla.getSemillaObtenidaEn()).isAfter(conSemilla.getCompromisoPublicadoEn());
        // obtenida una vez, no cambia
        assertThat(semillaRifaService.obtener(rifa).getSemilla()).isEqualTo(conSemilla.getSemilla());
    }

    // --- cierre a mano ------------------------------------------------------------------------------

    @Test
    void sinVentasTambienSeCierraConLaListaVacia() {
        RifaDetalle cerrada = cierreVentaService.cerrar(organizador, rifa);

        assertThat(cerrada.listaHash()).isEqualTo(SelloTorneo.sha256(""));
    }

    @Test
    void laRifaDeOtroNoSeCierra() {
        Throwable error = catchThrowable(() -> cierreVentaService.cerrar(otro, rifa));

        assertThat(error).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA);
        });
    }

    @Test
    void unBorradorNoSeCierra() {
        RifaDetalle borrador = rifaService.crear(organizador, new RifaRequest("Borrador", null, 1, 50,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, SORTEO, null, null));

        assertThatConflicto(() -> cierreVentaService.cerrar(organizador, borrador.id()), CodigoError.RIFA_NO_EDITABLE);
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private VentaRifaResultado comprar(Long usuario, List<Integer> numeros) {
        ventaRifaService.reservar(usuario, rifa, numeros);
        VentaRifaResultado compra = ventaRifaService.comprar(usuario, rifa, numeros, "compra-" + usuario + numeros);
        entityManager.flush();
        entityManager.clear();
        return compra;
    }

    /** La venta terminó hace un minuto; el sorteo sigue después (CHECK sorteada_en > venta_hasta). */
    private void terminarLaVenta(Long id) {
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '2 hours', venta_hasta = now() - interval '1 minute' "
                + "WHERE id = ?", id);
        entityManager.clear();
    }

    private String auditoria(Long id) {
        return jdbc.queryForObject("SELECT detalle->>'motivo' FROM auditoria WHERE accion = 'CIERRE_VENTA' "
                + "AND entidad = 'RIFA' AND entidad_id = ?", String.class, String.valueOf(id));
    }

    /** Una rifa del 1 al 100 a $500, sin torneo, publicada y con la venta abierta desde hace una hora. */
    private Long enVenta() {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa del cierre", null, 1, 100,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, SORTEO, null, null));
        rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(new PremioRifaRequest(new BigDecimal("100"), null)));
        rifaService.publicar(organizador, creada.id());
        // publicar exige una venta futura; para comprar, tiene que haber empezado
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", creada.id());
        entityManager.clear();
        return creada.id();
    }

    private static void assertThatConflicto(ThrowingCallable llamada, CodigoError codigo) {
        assertThatThrownBy(llamada).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCodigo()).isEqualTo(codigo);
        });
    }

    private Usuario usuario(String nombre) {
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
        return usuarioRepository.save(usuario);
    }
}
