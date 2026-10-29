package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.dto.NumeroOcupado;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.ReservaRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
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
 * La venta de números (PICA-222) contra Postgres, con las pruebas de aceptación de HU-403: número vendido, fuera de
 * rango, fuera de la ventana y compra confirmada; las compras simultáneas están en VentaRifaConcurrenteIntegracionTest.
 * La rifa sale a la venta con los gastos administrativos de V26 (10 %) y una reserva dura 10 minutos.
 * Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class VentaRifaServiceIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant SORTEO = EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS);

    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
    @Autowired private RifaPublicaService rifaPublicaService;
    @Autowired private TicketRepository ticketRepository;
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
        organizador = usuario("org_venta").getId();
        comprador = usuario("comprador_venta").getId();
        otro = usuario("otro_venta").getId();
        rifa = enVentaAbierta();
    }

    // --- pruebas de aceptación de HU-403 --------------------------------------------------------------

    @Test
    void unNumeroDisponibleSeCompraYQuedaDelComprador() {
        ReservaRifa reserva = ventaRifaService.reservar(comprador, rifa, List.of(6, 5));

        assertThat(reserva.numeros()).containsExactly(5, 6);
        assertThat(reserva.monto()).isEqualByComparingTo("1000");
        assertThat(reserva.gastosAdministrativos()).isEqualByComparingTo("100");
        assertThat(reserva.total()).isEqualByComparingTo("1100");
        assertThat(reserva.venceEn()).isCloseTo(Instant.now().plus(10, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));

        VentaRifaResultado compra = ventaRifaService.comprar(comprador, rifa, List.of(5, 6), "compra-1");

        assertThat(compra.ticketCodigo()).startsWith("R" + rifa + "-");
        assertThat(compra.numeros()).containsExactly(5, 6);
        assertThat(compra.total()).isEqualByComparingTo("1100");
        assertThat(compra.pozoRifa()).isEqualByComparingTo("1000");
        Ticket ticket = ticketRepository.findById(compra.ticketId()).orElseThrow();
        assertThat(ticket.getComprador().getId()).isEqualTo(comprador);
        assertThat(ticket.getMonto()).isEqualByComparingTo("1000");
        assertThat(ticket.getComision()).isEqualByComparingTo("100");
        assertThat(ticket.getMovimiento().getMonto()).isEqualByComparingTo("1100");
        assertThat(rifaPublicaService.numerosOcupados(rifa)).extracting(NumeroOcupado::numero, NumeroOcupado::estado)
                .containsExactly(tuple(5, EstadoNumero.VENDIDO), tuple(6, EstadoNumero.VENDIDO));
    }

    @Test
    void unNumeroYaVendidoNoSePuedeComprar() {
        ventaRifaService.reservar(comprador, rifa, List.of(7));
        ventaRifaService.comprar(comprador, rifa, List.of(7), "compra-1");

        assertThatConflicto(() -> ventaRifaService.reservar(otro, rifa, List.of(7, 8)),
                CodigoError.NUMERO_NO_DISPONIBLE);
        assertThat(numerosDelError(() -> ventaRifaService.reservar(otro, rifa, List.of(7, 8)))).containsExactly(7);
    }

    @Test
    void unNumeroFueraDelRangoEsInvalido() {
        Throwable error = catchThrowable(() -> ventaRifaService.reservar(comprador, rifa, List.of(5, 101)));

        assertThat(error).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCodigo()).isEqualTo(CodigoError.VALIDACION);
            assertThat(errores(e)).extracting(ErrorCampo::campo, ErrorCampo::codigo)
                    .containsExactly(tuple("numeros", ErrorCampo.Codigo.RANGO_INVALIDO));
        });
    }

    @Test
    void antesDeLaAperturaODespuesDelCierreNoSeVende() {
        jdbc.update("UPDATE rifa SET venta_desde = now() + interval '1 hour' WHERE id = ?", rifa);
        entityManager.clear();
        assertThatConflicto(() -> ventaRifaService.reservar(comprador, rifa, List.of(5)), CodigoError.VENTA_CERRADA);

        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '2 days', venta_hasta = now() - interval '1 hour'"
                + " WHERE id = ?", rifa);
        entityManager.clear();
        assertThatConflicto(() -> ventaRifaService.reservar(comprador, rifa, List.of(5)), CodigoError.VENTA_CERRADA);
        assertThatConflicto(() -> ventaRifaService.comprar(comprador, rifa, List.of(5), "compra-1"),
                CodigoError.VENTA_CERRADA);
    }

    // --- reserva ------------------------------------------------------------------------------------

    @Test
    void unNumeroReservadoPorOtroNoEstaDisponible() {
        ventaRifaService.reservar(comprador, rifa, List.of(9));

        assertThatConflicto(() -> ventaRifaService.reservar(otro, rifa, List.of(9)), CodigoError.NUMERO_NO_DISPONIBLE);
        assertThatConflicto(() -> ventaRifaService.comprar(otro, rifa, List.of(9), "compra-otro"),
                CodigoError.NUMERO_NO_DISPONIBLE);
    }

    @Test
    void reservarOtraVezReemplazaLaReservaYCancelarLaLibera() {
        ventaRifaService.reservar(comprador, rifa, List.of(1, 2));
        ventaRifaService.reservar(comprador, rifa, List.of(3));

        assertThat(rifaPublicaService.numerosOcupados(rifa)).extracting(NumeroOcupado::numero).containsExactly(3);

        ventaRifaService.cancelarReserva(comprador, rifa);
        assertThat(rifaPublicaService.numerosOcupados(rifa)).isEmpty();
    }

    @Test
    void conLaReservaVencidaNoSeCompraYOtroLaPuedeTomar() {
        ventaRifaService.reservar(comprador, rifa, List.of(4));
        jdbc.update("UPDATE numero SET creado_en = now() - interval '11 minutes' WHERE rifa_id = ?", rifa);
        entityManager.clear();

        assertThatConflicto(() -> ventaRifaService.comprar(comprador, rifa, List.of(4), "compra-1"),
                CodigoError.RESERVA_VENCIDA);
        assertThat(ventaRifaService.reservar(otro, rifa, List.of(4)).numeros()).containsExactly(4);
    }

    @Test
    void sinReservaNoSeCompra() {
        assertThatConflicto(() -> ventaRifaService.comprar(comprador, rifa, List.of(10), "compra-1"),
                CodigoError.RESERVA_VENCIDA);
    }

    @Test
    void elOrganizadorNoCompraSuPropiaRifa() {
        assertThatConflicto(() -> ventaRifaService.reservar(organizador, rifa, List.of(5)), CodigoError.RIFA_PROPIA);
    }

    // --- idempotencia -------------------------------------------------------------------------------

    @Test
    void laMismaCompraRepetidaDevuelveElMismoTicketSinCobrarOtraVez() {
        ventaRifaService.reservar(comprador, rifa, List.of(11));
        VentaRifaResultado primera = ventaRifaService.comprar(comprador, rifa, List.of(11), "compra-1");

        VentaRifaResultado segunda = ventaRifaService.comprar(comprador, rifa, List.of(11), "compra-1");

        assertThat(segunda.ticketId()).isEqualTo(primera.ticketId());
        assertThat(segunda.ticketCodigo()).isEqualTo(primera.ticketCodigo());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento WHERE referencia_externa LIKE '%compra-1'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void laClaveUsadaParaOtraCompraEsUnConflicto() {
        ventaRifaService.reservar(comprador, rifa, List.of(12));
        ventaRifaService.comprar(comprador, rifa, List.of(12), "compra-1");
        ventaRifaService.reservar(comprador, rifa, List.of(13));

        assertThatConflicto(() -> ventaRifaService.comprar(comprador, rifa, List.of(13), "compra-1"),
                CodigoError.CLAVE_IDEMPOTENCIA_USADA);
    }

    @Test
    void sinClaveNoSeCompra() {
        assertThatThrownBy(() -> ventaRifaService.comprar(comprador, rifa, List.of(5), " "))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(errores(e)).extracting(ErrorCampo::campo).containsExactly("Idempotency-Key"));
    }

    // --- ayudas -------------------------------------------------------------------------------------

    /** Una rifa del 1 al 100 a $500, publicada y con la venta abierta desde hace una hora. */
    private Long enVentaAbierta() {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa del club", null, 1, 100,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, SORTEO, null, null));
        rifaService.reemplazarPremios(organizador, creada.id(), List.of(new PremioRifaRequest(new BigDecimal("100"), null)));
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

    @SuppressWarnings("unchecked")
    private static List<Integer> numerosDelError(ThrowingCallable llamada) {
        return (List<Integer>) ((ApiException) catchThrowable(llamada)).getPropiedades().get("numeros");
    }

    @SuppressWarnings("unchecked")
    private static List<ErrorCampo> errores(ApiException e) {
        return (List<ErrorCampo>) e.getPropiedades().get("errores");
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
