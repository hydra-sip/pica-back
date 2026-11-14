package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.SorteoRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.GanadorRifa;
import com.hydra.pica.plataforma_pica.rifa.dto.LineaListaSellada;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    // los tests no salen a internet: drand contesta un valor fijo por ronda
    @MockitoSpyBean private Drand drand;

    private Long organizador;
    private Long comprador;
    private Long otro;
    private Long rifa;

    @BeforeEach
    void escenario() {
        doAnswer(i -> {
            long ronda = i.getArgument(0);
            return new Drand.Pulso(ronda, SelloTorneo.sha256("ronda:" + ronda), "firma");
        }).when(drand).pulso(anyLong());
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
        List<Integer> esperados = sinRepetirComprador(SorteoRifa.orden(sorteo.semilla(), List.of(3, 12, 25, 40, 77)));
        assertThat(sorteo.premios()).extracting(GanadorRifa::numeroGanador)
                .containsExactly(esperados.get(0), esperados.get(1));
        // en el portal se ve quién compró cada número ganador
        assertThat(sorteo.premios()).extracting(GanadorRifa::ganador)
                .containsExactly(compradorDe(esperados.get(0)), compradorDe(esperados.get(1)));
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

        assertThat(sorteo.versionSorteo()).isEqualTo(2);
        String texto = sorteo.lista().stream().map(l -> l.numero() + ";" + l.ticketCodigo() + ";" + l.comprador())
                .collect(Collectors.joining("\n"));
        assertThat(SelloTorneo.sha256(texto)).isEqualTo(sorteo.listaHash());
        assertThat(SelloTorneo.sha256(sorteo.listaHash() + ":" + sorteo.fechaSorteo())).isEqualTo(sorteo.compromisoHash());
        assertThat(sorteo.semillaObtenidaEn()).isAfter(sorteo.compromisoPublicadoEn());
        // como lo haría un auditor: el orden, salteando al comprador que ya ganó, solo con lo publicado
        Map<Integer, String> compradorDe = sorteo.lista().stream()
                .collect(Collectors.toMap(LineaListaSellada::numero, LineaListaSellada::comprador));
        List<Integer> ganadores = new ArrayList<>();
        Set<String> yaGanaron = new HashSet<>();
        for (Integer numero : SorteoRifa.orden(sorteo.semilla(), List.copyOf(compradorDe.keySet()))) {
            if (ganadores.size() < sorteo.premios().size() && yaGanaron.add(compradorDe.get(numero))) {
                ganadores.add(numero);
            }
        }
        assertThat(sorteo.premios()).extracting(GanadorRifa::numeroGanador).containsExactlyElementsOf(ganadores);
    }

    // --- CR-06: nadie gana dos veces -----------------------------------------------------------------

    @Test
    void laListaSelladaLlevaElCompradorAnonimoDeCadaNumero() {
        cerrarConElSorteoVencido();

        // C1 es quien compró el número más bajo (3 y 12), C2 el siguiente (25, 40 y 77)
        assertThat(rifaPublicaService.listaSellada(rifa)).extracting(LineaListaSellada::numero,
                LineaListaSellada::comprador).containsExactly(tuple(3, "C1"), tuple(12, "C1"), tuple(25, "C2"),
                tuple(40, "C2"), tuple(77, "C2"));
        assertThat(jdbc.queryForObject("SELECT version_sorteo FROM rifa WHERE id = ?", Integer.class, rifa))
                .isEqualTo(2);
    }

    @Test
    void unCompradorConVariosNumerosGanaUnSoloPremioYSiNoAlcanzanLosCompradoresElPremioQuedaSinGanador() {
        rifa = enVenta(List.of(new PremioRifaRequest(new BigDecimal("50"), null),
                new PremioRifaRequest(new BigDecimal("30"), null), new PremioRifaRequest(new BigDecimal("20"), null)));
        comprar(comprador, List.of(3, 12, 40, 58, 77, 91));
        comprar(otro, List.of(25));
        cerrarConElSorteoVencido();

        RifaDetalle sorteada = sorteoRifaService.sortear(organizador, rifa);

        List<Integer> numeros = sorteada.premios().stream().map(PremioRifaDetalle::numeroGanador).toList();
        assertThat(numeros.subList(0, 2)).extracting(this::compradorDe)
                .containsExactlyInAnyOrder("comprador_sorteo", "otro_sorteo");
        assertThat(numeros.get(2)).isNull();
    }

    @Test
    void unaRifaSelladaAntesDelCambioSeSorteaComoAntes() {
        cerrarConElSorteoVencido();
        // como las que ya estaban selladas al migrar (V51)
        jdbc.update("UPDATE rifa SET version_sorteo = 1 WHERE id = ?", rifa);
        entityManager.clear();

        sorteoRifaService.sortear(organizador, rifa);
        entityManager.flush();
        entityManager.clear();

        SorteoRifaDetalle sorteo = rifaPublicaService.sorteo(rifa);
        List<Integer> orden = SorteoRifa.orden(sorteo.semilla(), List.of(3, 12, 25, 40, 77));
        assertThat(sorteo.versionSorteo()).isEqualTo(1);
        assertThat(sorteo.premios()).extracting(GanadorRifa::numeroGanador).containsExactly(orden.get(0), orden.get(1));
        assertThat(sorteo.lista()).extracting(LineaListaSellada::comprador).containsOnlyNulls();
    }

    // --- pruebas de aceptación de HU-503 --------------------------------------------------------------

    @Test
    void laSemillaEsLaDeDrandEnLaRondaVigenteEnLaFechaDelSorteo() {
        cerrarConElSorteoVencido();
        sorteoRifaService.sortear(organizador, rifa);
        entityManager.flush();
        entityManager.clear();

        SorteoRifaDetalle sorteo = rifaPublicaService.sorteo(rifa);

        long ronda = drand.ronda(sorteo.fechaSorteo());
        assertThat(sorteo.semillaRonda()).isEqualTo(ronda);
        assertThat(sorteo.semilla()).isEqualTo(SelloTorneo.sha256("ronda:" + ronda));
        assertThat(sorteo.semillaFirma()).isEqualTo("firma");
        assertThat(sorteo.semillaUrl()).isEqualTo(
                "https://api.drand.sh/52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971/public/" + ronda);
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
    void conElSorteoAnticipadoSeSorteaUnPeriodoDeDrandDespuesDelCierre() {
        Object servicio = AopTestUtils.getTargetObject(cierreVentaService);
        ReflectionTestUtils.setField(servicio, "anticipado", true);
        try {
            RifaDetalle cerrada = cierreVentaService.cerrar(organizador, rifa);
            entityManager.flush();
            entityManager.clear();

            // sin esperar los 3 segundos: la tarea programada la sortea apenas llega la nueva fecha
            assertThat(sorteoRifaService.sortearSiLlegoLaFecha(rifa, cerrada.fechaSorteo())).isTrue();

            RifaDetalle sorteada = rifaService.detalle(organizador, rifa);
            assertThat(sorteada.estado()).isEqualTo(EstadoRifa.SORTEADA);
            assertThat(sorteada.premios()).allSatisfy(p -> assertThat(p.numeroGanador()).isNotNull());
            assertThat(rifaPublicaService.sorteo(rifa).semillaRonda()).isEqualTo(drand.ronda(cerrada.fechaSorteo()));
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

    /**
     * La venta terminó hace dos horas y se cerró sola hace una y media, antes del sorteo, que ya llegó hace una (CHECK
     * sorteada_en > venta_hasta).
     */
    private void cerrarConElSorteoVencido() {
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '3 hours', venta_hasta = now() - interval '2 hours', "
                + "sorteada_en = now() - interval '1 hour' WHERE id = ?", rifa);
        entityManager.clear();
        cierreVentaService.cerrarSiVencio(rifa, Instant.now().minus(90, ChronoUnit.MINUTES));
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

    /** El username de quien compró ese número en la rifa del test. */
    private String compradorDe(int numero) {
        return jdbc.queryForObject("""
                SELECT u.username FROM numero n JOIN ticket t ON t.id = n.ticket_id JOIN usuario u ON u.id = t.comprador_id
                WHERE n.rifa_id = ? AND n.numero = ?""", String.class, rifa, numero);
    }

    /** El orden sin los números de un comprador que ya salió, como sortea la versión 2 (CR-06). */
    private List<Integer> sinRepetirComprador(List<Integer> orden) {
        Set<String> yaSalieron = new HashSet<>();
        return orden.stream().filter(n -> yaSalieron.add(compradorDe(n))).toList();
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
