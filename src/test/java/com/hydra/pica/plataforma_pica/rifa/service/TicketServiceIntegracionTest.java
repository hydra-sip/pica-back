package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.TicketDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.VentaRifaResultado;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El ticket digital (PICA-224) contra Postgres, con las pruebas de aceptación de HU-404. La rifa sale a la venta con
 * los gastos administrativos de V26 (10 %). Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class TicketServiceIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant SORTEO = EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS);

    @Autowired private TicketService ticketService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
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
        organizador = usuario("org_ticket").getId();
        comprador = usuario("comprador_ticket").getId();
        otro = usuario("otro_ticket").getId();
        rifa = enVentaAbierta();
    }

    // --- pruebas de aceptación de HU-404 --------------------------------------------------------------

    @Test
    void unaVentaNoConfirmadaNoTieneTicket() {
        ventaRifaService.reservar(comprador, rifa, List.of(3, 4));

        assertThat(ticketService.misTickets(comprador)).isEmpty();
        assertThatNoEncontrado(() -> ticketService.detalle(comprador, 999_999L));
    }

    @Test
    void alConfirmarLaVentaElTicketSeGeneraConLaNumeracionYElComprobante() {
        VentaRifaResultado compra = comprar(List.of(9, 5));

        TicketDetalle ticket = ticketService.detalle(comprador, compra.ticketId());

        assertThat(ticket.codigo()).isEqualTo(compra.ticketCodigo());
        assertThat(ticket.numeros()).containsExactly(5, 9);
        assertThat(ticket.monto()).isEqualByComparingTo("1000");
        assertThat(ticket.comision()).isEqualByComparingTo("100");
        assertThat(ticket.moneda()).isEqualTo("ARS");
        assertThat(ticket.rifaId()).isEqualTo(rifa);
        assertThat(ticket.rifaTitulo()).isEqualTo("Rifa del club");
        assertThat(ticket.rifaEstado()).isEqualTo(EstadoRifa.EN_VENTA);
        assertThat(ticket.compradorNombre()).isEqualTo("comprador_ticket Test");
        assertThat(ticket.esComprador()).isTrue();
        assertThat(ticket.ventanaReclamoAbierta()).isFalse();
    }

    @Test
    void elTicketDescargadoCoincideConLaCompra() {
        VentaRifaResultado compra = comprar(List.of(12));

        TicketDetalle ticket = ticketService.detalle(comprador, compra.ticketId());

        assertThat(ticket.id()).isEqualTo(compra.ticketId());
        assertThat(ticket.codigo()).isEqualTo(compra.ticketCodigo());
        assertThat(ticket.numeros()).isEqualTo(compra.numeros());
        assertThat(ticket.monto()).isEqualByComparingTo(compra.monto());
        assertThat(ticket.comision()).isEqualByComparingTo(compra.gastosAdministrativos());
        assertThat(ticket.monto().add(ticket.comision())).isEqualByComparingTo(compra.total());
        // la base guarda microsegundos
        assertThat(ticket.creadoEn()).isCloseTo(compra.compradoEn(), within(1, ChronoUnit.MILLIS));
    }

    // --- quién lo ve --------------------------------------------------------------------------------

    @Test
    void elTicketDeOtroNoExiste() {
        VentaRifaResultado compra = comprar(List.of(20));

        assertThatNoEncontrado(() -> ticketService.detalle(otro, compra.ticketId()));
    }

    @Test
    @WithMockUser(authorities = "RIFA_ADMINISTRAR")
    void unAdministradorVeElTicketDeCualquiera() {
        VentaRifaResultado compra = comprar(List.of(21));

        TicketDetalle ticket = ticketService.detalle(otro, compra.ticketId());

        assertThat(ticket.compradorId()).isEqualTo(comprador);
        assertThat(ticket.esComprador()).isFalse();
    }

    // --- mis tickets --------------------------------------------------------------------------------

    @Test
    void misTicketsTraeLosDelUsuarioElMasNuevoPrimero() {
        VentaRifaResultado primera = comprar(List.of(30));
        VentaRifaResultado segunda = comprar(List.of(31, 32));
        ventaRifaService.reservar(otro, rifa, List.of(40));
        ventaRifaService.comprar(otro, rifa, List.of(40), "compra-otro");

        assertThat(ticketService.misTickets(comprador)).extracting(TicketDetalle::id)
                .containsExactly(segunda.ticketId(), primera.ticketId());
    }

    // --- la ventana de reclamo (HU-506) ------------------------------------------------------------

    @Test
    void laVentanaDeReclamoQuedaAbiertaLas48HorasDespuesDelSorteo() {
        VentaRifaResultado compra = comprar(List.of(50));

        sorteadaHace(47);
        assertThat(ticketService.detalle(comprador, compra.ticketId()).ventanaReclamoAbierta()).isTrue();

        sorteadaHace(49);
        assertThat(ticketService.detalle(comprador, compra.ticketId()).ventanaReclamoAbierta()).isFalse();
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private VentaRifaResultado comprar(List<Integer> numeros) {
        ventaRifaService.reservar(comprador, rifa, numeros);
        VentaRifaResultado compra = ventaRifaService.comprar(comprador, rifa, numeros, "compra-" + numeros);
        entityManager.flush();
        entityManager.clear();
        return compra;
    }

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

    /** Todavía no hay sorteo de rifas (PICA-265): se marca a mano con el momento en que se obtuvo la semilla. */
    private void sorteadaHace(int horas) {
        jdbc.update("UPDATE rifa SET estado = 'SORTEADA', semilla_obtenida_en = now() - make_interval(hours => ?) "
                + "WHERE id = ?", horas, rifa);
        entityManager.clear();
    }

    private static void assertThatNoEncontrado(ThrowingCallable llamada) {
        assertThatThrownBy(llamada).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getCodigo()).isEqualTo(CodigoError.TICKET_NO_ENCONTRADO);
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
