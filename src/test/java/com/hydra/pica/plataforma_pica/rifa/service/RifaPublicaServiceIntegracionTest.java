package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.billetera.domain.TipoMovimiento;
import com.hydra.pica.plataforma_pica.billetera.service.CobroService;
import com.hydra.pica.plataforma_pica.billetera.service.SolicitudCobro;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import com.hydra.pica.plataforma_pica.rifa.dto.NumeroOcupado;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaPublicaResumen;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.TicketRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las rifas para quien compra (PICA-221) contra Postgres: el listado de las que están en venta, el detalle y la
 * grilla, con la reserva vencida como libre (10 minutos por defecto). Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class RifaPublicaServiceIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant SORTEO = EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS);

    @Autowired private RifaPublicaService rifaPublicaService;
    @Autowired private RifaService rifaService;
    @Autowired private RifaRepository rifaRepository;
    @Autowired private NumeroRepository numeroRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private CobroService cobroService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Usuario comprador;

    @BeforeEach
    void usuarios() {
        organizador = usuario("org_publica").getId();
        comprador = usuario("comprador_publica");
    }

    // --- listado ------------------------------------------------------------

    @Test
    void listaSoloLasEnVentaConLaVentaSinTerminarLaQueCierraAntesPrimero() {
        Long cierraDespues = enVenta("Cierra después", EN_UNA_SEMANA);
        Long cierraAntes = enVenta("Cierra antes", EN_UNA_SEMANA.minus(1, ChronoUnit.DAYS));
        rifaService.crear(organizador, request("Borrador", MANIANA, EN_UNA_SEMANA));
        Long terminada = enVenta("Terminada", EN_UNA_SEMANA);
        // la venta ya terminó: no se puede comprar aunque siga EN_VENTA hasta el sorteo
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '3 days', venta_hasta = now() - interval '1 day',"
                + " sorteada_en = now() + interval '1 day' WHERE id = ?", terminada);
        Long eliminada = enVenta("Eliminada", EN_UNA_SEMANA);
        jdbc.update("UPDATE rifa SET eliminado_en = now() WHERE id = ?", eliminada);

        List<RifaPublicaResumen> rifas = rifaPublicaService.listarEnVenta(PageRequest.of(0, 20)).getContent();

        assertThat(rifas).extracting(RifaPublicaResumen::id).containsExactly(cierraAntes, cierraDespues);
    }

    // --- detalle ------------------------------------------------------------

    @Test
    void elDetalleTraeLosPremiosYLaFechaDelSorteo() {
        Long id = enVenta("Rifa del club", EN_UNA_SEMANA);

        RifaPublicaDetalle rifa = rifaPublicaService.detalle(id);

        assertThat(rifa.titulo()).isEqualTo("Rifa del club");
        assertThat(rifa.torneo()).isNull();
        assertThat(rifa.pctDestinadoTorneo()).isZero();
        assertThat(rifa.fechaSorteo()).isNotNull();
        assertThat(rifa.premios()).extracting(PremioRifaDetalle::puesto, PremioRifaDetalle::pct)
                .containsExactly(tuple(1, 60), tuple(2, 40));
    }

    @Test
    void unBorradorNoExisteParaElPublico() {
        RifaDetalle borrador = rifaService.crear(organizador, request("Borrador", MANIANA, EN_UNA_SEMANA));

        assertThatThrownBy(() -> rifaPublicaService.detalle(borrador.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA);
                });
        assertThatThrownBy(() -> rifaPublicaService.numerosOcupados(borrador.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
    }

    // --- grilla -------------------------------------------------------------

    @Test
    void laGrillaTraeLosVendidosYLosReservadosVigentesEnOrden() {
        Long id = enVenta("Rifa del club", EN_UNA_SEMANA);
        Rifa rifa = rifaRepository.findById(id).orElseThrow();
        numeroRepository.save(Numero.reservado(rifa, 42, comprador));
        vender(rifa, 7, 13);

        assertThat(rifaPublicaService.numerosOcupados(id))
                .extracting(NumeroOcupado::numero, NumeroOcupado::estado)
                .containsExactly(tuple(7, EstadoNumero.VENDIDO), tuple(13, EstadoNumero.VENDIDO),
                        tuple(42, EstadoNumero.RESERVADO));
    }

    @Test
    void unaReservaVencidaCuentaComoLibre() {
        Long id = enVenta("Rifa del club", EN_UNA_SEMANA);
        Rifa rifa = rifaRepository.findById(id).orElseThrow();
        numeroRepository.saveAndFlush(Numero.reservado(rifa, 42, comprador));
        jdbc.update("UPDATE numero SET creado_en = now() - interval '11 minutes' WHERE rifa_id = ? AND numero = 42", id);
        numeroRepository.saveAndFlush(Numero.reservado(rifa, 43, comprador));
        jdbc.update("UPDATE numero SET creado_en = now() - interval '9 minutes' WHERE rifa_id = ? AND numero = 43", id);
        // que la grilla lea los creado_en de la base y no los que quedaron en la sesión
        entityManager.clear();

        assertThat(rifaPublicaService.numerosOcupados(id)).extracting(NumeroOcupado::numero).containsExactly(43);
    }

    // --- ayudas -------------------------------------------------------------

    /** Una rifa publicada, con la venta desde mañana hasta {@code ventaHasta} y dos premios. */
    private Long enVenta(String titulo, Instant ventaHasta) {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest(titulo, null, 1, 100,
                new BigDecimal("500"), MANIANA, ventaHasta, SORTEO, null, null));
        rifaService.reemplazarPremios(organizador, creada.id(),
                List.of(new PremioRifaRequest(new BigDecimal("60"), null), new PremioRifaRequest(new BigDecimal("40"), null)));
        rifaService.publicar(organizador, creada.id());
        return creada.id();
    }

    private static RifaRequest request(String titulo, Instant ventaDesde, Instant ventaHasta) {
        return new RifaRequest(titulo, null, 1, 100, new BigDecimal("500"), ventaDesde, ventaHasta, null, null, null);
    }

    /** Como queda una compra: el cobro, el ticket y los números VENDIDO. */
    private void vender(Rifa rifa, int... numeros) {
        String clave = "test-" + rifa.getId() + "-" + numeros[0];
        BigDecimal monto = rifa.getPrecioNumero().multiply(BigDecimal.valueOf(numeros.length));
        Movimiento pago = cobroService.cobrar(new SolicitudCobro(comprador.getId(), TipoMovimiento.COMPRA_NUMERO, monto,
                null, null, clave, "Compra de prueba"));
        Ticket ticket = new Ticket();
        ticket.setCodigo(clave);
        ticket.setRifa(rifa);
        ticket.setComprador(comprador);
        ticket.setMovimiento(pago);
        ticket.setMonto(monto);
        ticket.setComision(BigDecimal.ZERO);
        ticket.setClaveIdempotencia(clave);
        ticketRepository.save(ticket);
        for (int numero : numeros) {
            numeroRepository.save(Numero.vendido(rifa, numero, ticket));
        }
        numeroRepository.flush();
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
