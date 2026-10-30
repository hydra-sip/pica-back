package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.SorteoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.GanadorRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.SorteoRifaDetalle;
import com.hydra.pica.plataforma_pica.rifa.dto.TicketDetalle;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * El sorteo de una rifa (PICA-265) contra Postgres, con las pruebas de aceptación de HU-504. La tarea programada está
 * apagada en los tests: se la llama a mano. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class SorteoRifaIntegracionTest {

    private static final Instant MANIANA = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant SORTEO = EN_UNA_SEMANA.plus(1, ChronoUnit.DAYS);

    @Autowired private SorteoRifaService sorteoRifaService;
    @Autowired private SorteoAutomaticoRifa sorteoAutomaticoRifa;
    @Autowired private CierreVentaService cierreVentaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private RifaService rifaService;
    @Autowired private RifaPublicaService rifaPublicaService;
    @Autowired private TicketService ticketService;
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
        organizador = usuario("org_sorteo").getId();
        comprador = usuario("comprador_sorteo").getId();
        otro = usuario("otro_sorteo").getId();
        rifa = enVenta();
        comprar(comprador, List.of(3, 12));
        comprar(otro, List.of(25, 40, 77));
    }

    // --- pruebas de aceptación de HU-504 --------------------------------------------------------------

    @Test
    void alLlegarLaFechaSeSorteaSolaYElResultadoQuedaConLaSemillaYElCompromiso() {
        cerrarConElSorteoVencido();

        assertThat(sorteoAutomaticoRifa.sortearLasQueLlegaron()).isEqualTo(1);

        entityManager.clear();
        SorteoRifaDetalle sorteo = rifaPublicaService.sorteo(rifa);
        List<Integer> orden = SorteoRifa.orden(sorteo.semilla(), List.of(3, 12, 25, 40, 77));
        assertThat(sorteo.premios()).extracting(GanadorRifa::numeroGanador)
                .containsExactly(orden.get(0), orden.get(1));
        // en el portal se ve quién compró cada número ganador
        assertThat(sorteo.premios()).extracting(GanadorRifa::ganador)
                .containsExactly(compradorDe(orden.get(0)), compradorDe(orden.get(1)));
        assertThat(rifaService.detalle(organizador, rifa).estado()).isEqualTo(EstadoRifa.SORTEADA);
        assertThat(jdbc.queryForObject("SELECT detalle->>'semilla' FROM auditoria WHERE accion = 'SORTEO' "
                + "AND entidad = 'RIFA' AND entidad_id = ?", String.class, String.valueOf(rifa)))
                .isEqualTo(sorteo.semilla());
        assertThat(jdbc.queryForObject("SELECT detalle->>'compromisoHash' FROM auditoria WHERE accion = 'SORTEO' "
                + "AND entidad = 'RIFA' AND entidad_id = ?", String.class, String.valueOf(rifa)))
                .isEqualTo(sorteo.compromisoHash());
    }

    @Test
    void conLosDatosPublicadosCualquieraRecalculaElSorteo() {
        cerrarConElSorteoVencido();
        sorteoRifaService.sortear(organizador, rifa);
        entityManager.flush();
        entityManager.clear();

        SorteoRifaDetalle sorteo = rifaPublicaService.sorteo(rifa);

        String texto = sorteo.lista().stream().map(l -> l.numero() + ";" + l.ticketCodigo())
                .collect(Collectors.joining("\n"));
        assertThat(SelloTorneo.sha256(texto)).isEqualTo(sorteo.listaHash());
        assertThat(SelloTorneo.sha256(sorteo.listaHash() + ":" + sorteo.fechaSorteo())).isEqualTo(sorteo.compromisoHash());
        assertThat(sorteo.semillaObtenidaEn()).isAfter(sorteo.compromisoPublicadoEn());
        List<Integer> orden = SorteoRifa.orden(sorteo.semilla(), sorteo.lista().stream().map(l -> l.numero()).toList());
        assertThat(sorteo.premios().get(0).numeroGanador()).isEqualTo(orden.get(0));
    }

    @Test
    void sinUnaSemillaVerificadaNoSeSortea() {
        cerrarConElSorteoVencido();
        jdbc.update("UPDATE rifa SET compromiso_hash = NULL, compromiso_publicado_en = NULL WHERE id = ?", rifa);
        entityManager.clear();

        assertThatConflicto(() -> sorteoRifaService.sortear(organizador, rifa), CodigoError.COMPROMISO_NO_PUBLICADO);
    }

    // --- sorteo a mano --------------------------------------------------------------------------------

    @Test
    void antesDeLaFechaComprometidaElOrganizadorNoSortea() {
        cierreVentaService.cerrar(organizador, rifa);

        assertThatConflicto(() -> sorteoRifaService.sortear(organizador, rifa), CodigoError.SORTEO_ANTICIPADO);
        assertThat(sorteoAutomaticoRifa.sortearLasQueLlegaron()).isZero();
    }

    @Test
    void conElSorteoAnticipadoSeSorteaApenasSeCierraLaVenta() {
        cierreVentaService.cerrar(organizador, rifa);
        Object servicio = AopTestUtils.getTargetObject(sorteoRifaService);
        ReflectionTestUtils.setField(servicio, "anticipado", true);
        try {
            RifaDetalle sorteada = sorteoRifaService.sortear(organizador, rifa);

            assertThat(sorteada.estado()).isEqualTo(EstadoRifa.SORTEADA);
            assertThat(sorteada.premios()).allSatisfy(p -> assertThat(p.numeroGanador()).isNotNull());
        } finally {
            ReflectionTestUtils.setField(servicio, "anticipado", false);
        }
    }

    @Test
    void unaRifaSorteadaNoSeVuelveASortearNiSeSorteaUnaEnVenta() {
        Long sigueEnVenta = enVenta();
        cerrarConElSorteoVencido();
        sorteoRifaService.sortear(organizador, rifa);

        assertThatConflicto(() -> sorteoRifaService.sortear(organizador, rifa), CodigoError.RIFA_NO_EDITABLE);
        assertThatConflicto(() -> sorteoRifaService.sortear(organizador, sigueEnVenta), CodigoError.RIFA_NO_EDITABLE);
    }

    @Test
    void laRifaDeOtroNoSeSortea() {
        cerrarConElSorteoVencido();

        assertThatThrownBy(() -> sorteoRifaService.sortear(otro, rifa)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCodigo()).isEqualTo(CodigoError.RIFA_NO_ENCONTRADA));
    }

    // --- lo que se ve del sorteo ----------------------------------------------------------------------

    @Test
    void antesDelSorteoNoHayDatosDelSorteoNiFiguraEntreLasSorteadas() {
        cierreVentaService.cerrar(organizador, rifa);

        assertThatConflicto(() -> rifaPublicaService.sorteo(rifa), CodigoError.RIFA_NO_SORTEADA);
        assertThat(rifaPublicaService.listarSorteadas(org.springframework.data.domain.Pageable.ofSize(50)).getContent())
                .noneMatch(r -> r.id().equals(rifa));
    }

    @Test
    void elTicketDiceQuePremioGanoYLaRifaFiguraEntreLasSorteadas() {
        cerrarConElSorteoVencido();
        RifaDetalle sorteada = sorteoRifaService.sortear(organizador, rifa);
        entityManager.flush();
        entityManager.clear();

        Integer primero = sorteada.premios().get(0).numeroGanador();
        Long ganador = List.of(3, 12).contains(primero) ? comprador : otro;
        TicketDetalle ticket = ticketService.misTickets(ganador).get(0);
        assertThat(ticket.premiosGanados()).extracting(PremioRifaDetalle::puesto).contains(1);
        assertThat(rifaPublicaService.listarSorteadas(org.springframework.data.domain.Pageable.ofSize(50)).getContent())
                .anyMatch(r -> r.id().equals(rifa));
    }

    @Test
    void conMasPremiosQueVendidosLosQueSobranQuedanSinGanador() {
        rifa = enVenta(List.of(new PremioRifaRequest(new BigDecimal("50"), null),
                new PremioRifaRequest(new BigDecimal("30"), null), new PremioRifaRequest(new BigDecimal("20"), null)));
        comprar(comprador, List.of(9));
        cerrarConElSorteoVencido();

        RifaDetalle sorteada = sorteoRifaService.sortear(organizador, rifa);

        assertThat(sorteada.premios()).extracting(PremioRifaDetalle::numeroGanador).containsExactly(9, null, null);
    }

    // --- ayudas -------------------------------------------------------------------------------------

    private void comprar(Long usuario, List<Integer> numeros) {
        ventaRifaService.reservar(usuario, rifa, numeros);
        ventaRifaService.comprar(usuario, rifa, numeros, "compra-" + usuario + numeros);
        entityManager.flush();
        entityManager.clear();
    }

    /** La venta terminó y el sorteo ya llegó (CHECK sorteada_en > venta_hasta); después se cierra y se sella. */
    private void cerrarConElSorteoVencido() {
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '3 hours', venta_hasta = now() - interval '2 hours', "
                + "sorteada_en = now() - interval '1 hour' WHERE id = ?", rifa);
        entityManager.clear();
        cierreVentaService.cerrarSiVencio(rifa, Instant.now());
        entityManager.flush();
        entityManager.clear();
    }

    /** Una rifa del 1 al 100 a $500 con dos premios, sin torneo, publicada y con la venta abierta. */
    private Long enVenta() {
        return enVenta(List.of(new PremioRifaRequest(new BigDecimal("70"), "Primer premio"),
                new PremioRifaRequest(new BigDecimal("30"), "Segundo premio")));
    }

    private Long enVenta(List<PremioRifaRequest> premios) {
        RifaDetalle creada = rifaService.crear(organizador, new RifaRequest("Rifa del sorteo", null, 1, 100,
                new BigDecimal("500"), MANIANA, EN_UNA_SEMANA, SORTEO, null, null));
        rifaService.reemplazarPremios(organizador, creada.id(), premios);
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

    private String compradorDe(int numero) {
        return List.of(3, 12).contains(numero) ? "comprador_sorteo" : "otro_sorteo";
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
