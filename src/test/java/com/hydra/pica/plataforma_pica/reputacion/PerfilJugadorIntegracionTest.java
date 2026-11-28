package com.hydra.pica.plataforma_pica.reputacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hydra.pica.plataforma_pica.EscenarioTorneo;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionEquipoRequest;
import com.hydra.pica.plataforma_pica.inscripcion.service.InscripcionService;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionDetalle;
import com.hydra.pica.plataforma_pica.reputacion.dto.SancionRequest;
import com.hydra.pica.plataforma_pica.reputacion.service.FairPlayService;
import com.hydra.pica.plataforma_pica.reputacion.service.InsigniaService;
import com.hydra.pica.plataforma_pica.reputacion.service.SancionService;
import com.hydra.pica.plataforma_pica.rifa.dto.PremioRifaRequest;
import com.hydra.pica.plataforma_pica.rifa.dto.RifaRequest;
import com.hydra.pica.plataforma_pica.rifa.service.CierreVentaService;
import com.hydra.pica.plataforma_pica.rifa.service.Drand;
import com.hydra.pica.plataforma_pica.rifa.service.RifaService;
import com.hydra.pica.plataforma_pica.rifa.service.SorteoRifaService;
import com.hydra.pica.plataforma_pica.rifa.service.VentaRifaService;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioEdicionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las pruebas de aceptación del perfil público del jugador (HU-801, T801-2, PICA-419): el escenario se arma con los
 * servicios de la aplicación y el perfil se pide por HTTP, sin sesión. Solo se mira el JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({TestcontainersConfiguration.class, EscenarioTorneo.class})
@Transactional
class PerfilJugadorIntegracionTest {

    private static final Instant EN_UN_MES = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @Autowired private MockMvc mockMvc;
    @Autowired private EscenarioTorneo escenario;
    @Autowired private UsuarioEdicionService usuarioEdicionService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private SancionService sancionService;
    @Autowired private FairPlayService fairPlayService;
    @Autowired private TorneoService torneoService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private InscripcionService inscripcionService;
    @Autowired private BracketService bracketService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InsigniaService insigniaService;
    @Autowired private RifaService rifaService;
    @Autowired private VentaRifaService ventaRifaService;
    @Autowired private CierreVentaService cierreVentaService;
    @Autowired private SorteoRifaService sorteoRifaService;
    @Autowired private ObjectMapper objectMapper;

    @MockitoSpyBean private Drand drand;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long arbitro;

    @BeforeEach
    void preparar() {
        doAnswer(i -> {
            long ronda = i.getArgument(0);
            return new Drand.Pulso(ronda, SelloTorneo.sha256("ronda:" + ronda), "firma");
        }).when(drand).pulso(anyLong());
        organizador = escenario.usuario("org_perfil_jugador", "ORGANIZADOR").getId();
        arbitro = escenario.usuario("juez_perfil_jugador", "ARBITRO").getId();
    }

    @Test
    void sinSesionElPerfilDeUnJugadorMuestraSuIdentidadVisible() throws Exception {
        Long jugador = escenario.usuario("jugador_perfil", "PARTICIPANTE").getId();

        perfil(jugador)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jugador))
                .andExpect(jsonPath("$.username").value("jugador_perfil"))
                .andExpect(jsonPath("$.nombreCompleto").value("jugador_perfil Test"))
                .andExpect(jsonPath("$.avatar").isEmpty());
    }

    @Test
    void unUsuarioQueNoExisteDa404() throws Exception {
        perfil(Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    void unUsuarioDadoDeBajaDa404() throws Exception {
        Long jugador = escenario.usuario("jugador_baja", "PARTICIPANTE").getId();

        usuarioEdicionService.eliminar(jugador);

        perfil(jugador)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("USUARIO_NO_ENCONTRADO"));
    }

    @Test
    void unUsuarioBloqueadoSeMuestraIgual() throws Exception {
        Long jugador = escenario.usuario("jugador_bloqueado", "PARTICIPANTE").getId();

        usuarioEdicionService.bloquear(jugador, "Insultos en el chat", null);

        perfil(jugador)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("jugador_bloqueado"));
    }

    @Test
    void unJugadorNuevoTieneElHistorialEnCero() throws Exception {
        Long nuevo = escenario.usuario("jugador_nuevo", "PARTICIPANTE").getId();

        perfil(nuevo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historial.torneosJugados").value(0))
                .andExpect(jsonPath("$.historial.victorias").value(0))
                .andExpect(jsonPath("$.historial.derrotas").value(0))
                .andExpect(jsonPath("$.historial.podios").value(0));
    }

    @Test
    void despuesDeJugarYFinalizarUnTorneoElHistorialLoRefleja() throws Exception {
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa del perfil", "jugador_final_", 2);
        Long laFinal = escenario.partido(torneo, 1, 1);
        Long campeon = escenario.jugador(laFinal, "LOCAL");
        Long subcampeon = escenario.jugador(laFinal, "VISITANTE");

        jugar(laFinal, 2, 0);

        perfil(campeon)
                .andExpect(jsonPath("$.historial.torneosJugados").value(1))
                .andExpect(jsonPath("$.historial.victorias").value(1))
                .andExpect(jsonPath("$.historial.derrotas").value(0))
                .andExpect(jsonPath("$.historial.podios").value(1));
        perfil(subcampeon)
                .andExpect(jsonPath("$.historial.torneosJugados").value(1))
                .andExpect(jsonPath("$.historial.victorias").value(0))
                .andExpect(jsonPath("$.historial.derrotas").value(1))
                .andExpect(jsonPath("$.historial.podios").value(1));
    }

    @Test
    void unJugadorCuyoIndiceNuncaSeMovioTieneElFairPlayInicial() throws Exception {
        Long nuevo = escenario.usuario("jugador_fair_play", "PARTICIPANTE").getId();

        perfil(nuevo)
                .andExpect(jsonPath("$.fairPlay.valor").value(100))
                .andExpect(jsonPath("$.fairPlay.actualizadoEn").isEmpty());
    }

    @Test
    void despuesDeUnaSancionElPerfilMuestraElIndiceNuevoPeroNoLaSancion() throws Exception {
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa sancionada", "jugador_sancion_", 2);
        Long sancionado = escenario.jugador(escenario.partido(torneo, 1, 1), "LOCAL");
        SancionDetalle sancion = sancionService.aplicar(organizador, torneo,
                new SancionRequest(sancionado, null, Gravedad.MEDIA, "Conducta antideportiva"));
        // lo que hace FairPlayAutomatico después del commit, que este test no llega a hacer
        fairPlayService.registrarSancion(sancionado, torneo, sancion.id(), Gravedad.MEDIA);
        entityManager.flush();

        perfil(sancionado)
                .andExpect(jsonPath("$.fairPlay.valor").value(85))
                .andExpect(jsonPath("$.fairPlay.actualizadoEn").isNotEmpty());
    }

    @Test
    void unJugadorNuevoTieneLasListasVacias() throws Exception {
        Long nuevo = escenario.usuario("jugador_sin_torneos", "PARTICIPANTE").getId();

        perfil(nuevo)
                .andExpect(jsonPath("$.ultimosTorneos").isEmpty())
                .andExpect(jsonPath("$.rifasGanadas").isEmpty())
                .andExpect(jsonPath("$.insignias").isEmpty());
    }

    @Test
    void unTorneoFinalizadoApareceEnLosUltimosConElPuestoDeCadaUno() throws Exception {
        Usuario campeon = escenario.usuario("campeon_ultimos", "PARTICIPANTE");
        Usuario subcampeon = escenario.usuario("subcampeon_ultimos", "PARTICIPANTE");
        Instant fin = EN_UN_MES.plus(1, ChronoUnit.DAYS);
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa de los últimos", EN_UN_MES, fin,
                List.of(campeon, subcampeon));
        Long laFinal = escenario.partido(torneo, 1, 1);

        jugar(laFinal, ganaElDe(laFinal, campeon.getId()));

        perfil(campeon.getId())
                .andExpect(jsonPath("$.ultimosTorneos.length()").value(1))
                .andExpect(jsonPath("$.ultimosTorneos[0].torneoId").value(torneo))
                .andExpect(jsonPath("$.ultimosTorneos[0].slug").value("escenario-" + torneo))
                .andExpect(jsonPath("$.ultimosTorneos[0].nombre").value("Copa de los últimos"))
                .andExpect(jsonPath("$.ultimosTorneos[0].disciplina").value("Ajedrez"))
                .andExpect(jsonPath("$.ultimosTorneos[0].fechaFin").value(fin.toString()))
                .andExpect(jsonPath("$.ultimosTorneos[0].posicion").value(1))
                .andExpect(jsonPath("$.ultimosTorneos[0].equipo").isEmpty());
        perfil(subcampeon.getId())
                .andExpect(jsonPath("$.ultimosTorneos.length()").value(1))
                .andExpect(jsonPath("$.ultimosTorneos[0].posicion").value(2));
    }

    @Test
    void unTorneoEnCursoEnElQueJugoNoApareceEnLosUltimos() throws Exception {
        Long torneo = escenario.individualEnCurso(organizador, arbitro, "Copa en curso", "jugador_en_curso_", 4);
        Long partido = escenario.partido(torneo, 1, 1);
        Long ganador = escenario.jugador(partido, "LOCAL");

        jugar(partido, 1, 0);

        perfil(ganador)
                .andExpect(jsonPath("$.historial.victorias").value(1))
                .andExpect(jsonPath("$.historial.torneosJugados").value(0))
                .andExpect(jsonPath("$.ultimosTorneos").isEmpty());
    }

    @Test
    void conMasDeCincoTorneosVienenLosCincoMasRecientesPorFechaDeFinYPorIdSiEmpatan() throws Exception {
        Usuario jugador = escenario.usuario("jugador_de_seis", "PARTICIPANTE");
        Long a = finalizado(jugador, "Copa A", EN_UN_MES, EN_UN_MES.plus(3, ChronoUnit.DAYS));
        finalizado(jugador, "Copa B", EN_UN_MES, EN_UN_MES.plus(1, ChronoUnit.DAYS));
        Long c = finalizado(jugador, "Copa C", EN_UN_MES, EN_UN_MES.plus(6, ChronoUnit.DAYS));
        Long d = finalizado(jugador, "Copa D", EN_UN_MES, EN_UN_MES.plus(2, ChronoUnit.DAYS));
        Long e = finalizado(jugador, "Copa E", EN_UN_MES, EN_UN_MES.plus(5, ChronoUnit.DAYS));
        Long f = finalizado(jugador, "Copa F", EN_UN_MES, EN_UN_MES.plus(5, ChronoUnit.DAYS));

        perfil(jugador.getId())
                .andExpect(jsonPath("$.historial.torneosJugados").value(6))
                .andExpect(jsonPath("$.ultimosTorneos[*].torneoId").value(contains(
                        c.intValue(), f.intValue(), e.intValue(), a.intValue(), d.intValue())));
    }

    @Test
    void unTorneoSinFechaDeFinSeOrdenaPorSuFechaDeInicio() throws Exception {
        Usuario jugador = escenario.usuario("jugador_sin_fin", "PARTICIPANTE");
        Long antes = finalizado(jugador, "Copa de antes", EN_UN_MES.minus(10, ChronoUnit.DAYS),
                EN_UN_MES.minus(8, ChronoUnit.DAYS));
        Long sinFin = finalizado(jugador, "Copa sin fin", EN_UN_MES, null);
        Long despues = finalizado(jugador, "Copa de después", EN_UN_MES, EN_UN_MES.plus(5, ChronoUnit.DAYS));

        perfil(jugador.getId())
                .andExpect(jsonPath("$.ultimosTorneos[*].torneoId").value(contains(
                        despues.intValue(), sinFin.intValue(), antes.intValue())))
                .andExpect(jsonPath("$.ultimosTorneos[1].fechaFin").isEmpty());
    }

    @Test
    void enUnTorneoPorEquiposElIntegranteDeLaNominaLoVeConElNombreDelEquipo() throws Exception {
        Long porEquipos = torneoPorEquipos();
        Long capitanA = escenario.usuario("capitan_perfil_a", "PARTICIPANTE").getId();
        Long companieroA = escenario.usuario("companiero_perfil_a", "PARTICIPANTE").getId();
        Long capitanB = escenario.usuario("capitan_perfil_b", "PARTICIPANTE").getId();
        Long companieroB = escenario.usuario("companiero_perfil_b", "PARTICIPANTE").getId();
        inscribirEquipo(porEquipos, capitanA, "Los del perfil", companieroA);
        inscribirEquipo(porEquipos, capitanB, "Los otros", companieroB);
        cerrarYSortear(porEquipos);

        jugar(escenario.partido(porEquipos, 1, 1), capitanA, capitanB);

        perfil(companieroA)
                .andExpect(jsonPath("$.historial.torneosJugados").value(1))
                .andExpect(jsonPath("$.ultimosTorneos.length()").value(1))
                .andExpect(jsonPath("$.ultimosTorneos[0].torneoId").value(porEquipos))
                .andExpect(jsonPath("$.ultimosTorneos[0].equipo").value("Los del perfil"));
    }

    @Test
    void unJugadorQueGanoUnaRifaLaVeEnSuPerfil() throws Exception {
        Long comprador = escenario.usuario("comprador_perfil", "PARTICIPANTE").getId();

        Long rifa = rifaGanada(comprador, "Rifa del perfil", 7);

        perfil(comprador)
                .andExpect(jsonPath("$.rifasGanadas.length()").value(1))
                .andExpect(jsonPath("$.rifasGanadas[0].rifaId").value(rifa))
                .andExpect(jsonPath("$.rifasGanadas[0].rifaTitulo").value("Rifa del perfil"))
                .andExpect(jsonPath("$.rifasGanadas[0].puesto").value(1))
                .andExpect(jsonPath("$.rifasGanadas[0].numeroGanador").value(7))
                .andExpect(jsonPath("$.rifasGanadas[0].fecha").isNotEmpty());
    }

    @Test
    void unJugadorQueJugoSuPrimerTorneoVeLaInsignia() throws Exception {
        Usuario jugador = escenario.usuario("jugador_insignia", "PARTICIPANTE");

        finalizado(jugador, "Copa de la insignia", EN_UN_MES, EN_UN_MES.plus(1, ChronoUnit.DAYS));

        perfil(jugador.getId())
                .andExpect(jsonPath("$.insignias[*].codigo").value(hasItem("PRIMER_TORNEO_JUGADO")))
                .andExpect(jsonPath("$.insignias[?(@.codigo == 'PRIMER_TORNEO_JUGADO')].nombre").isNotEmpty())
                .andExpect(jsonPath("$.insignias[?(@.codigo == 'PRIMER_TORNEO_JUGADO')].otorgadaEn").isNotEmpty());
    }

    /** Un campo privado agregado por error (email, documento, datos bancarios) rompe este test. */
    @Test
    void elPerfilTraeSoloLasClavesPublicas() throws Exception {
        Usuario jugador = escenario.usuario("jugador_claves", "PARTICIPANTE");
        finalizado(jugador, "Copa de las claves", EN_UN_MES, EN_UN_MES.plus(1, ChronoUnit.DAYS));
        rifaGanada(jugador.getId(), "Rifa de las claves", 3);

        JsonNode perfil = json(jugador.getId());

        assertThat(claves(perfil)).containsExactlyInAnyOrder(
                "id", "username", "nombreCompleto", "avatar", "historial", "fairPlay",
                "ultimosTorneos", "rifasGanadas", "insignias");
        assertThat(claves(perfil.get("historial"))).containsExactlyInAnyOrder(
                "torneosJugados", "victorias", "derrotas", "podios");
        assertThat(claves(perfil.get("fairPlay"))).containsExactlyInAnyOrder("valor", "actualizadoEn");
        assertThat(perfil.get("ultimosTorneos")).hasSize(1);
        assertThat(claves(perfil.get("ultimosTorneos").get(0))).containsExactlyInAnyOrder(
                "torneoId", "slug", "nombre", "disciplina", "fechaFin", "posicion", "equipo");
        assertThat(perfil.get("rifasGanadas")).hasSize(1);
        assertThat(claves(perfil.get("rifasGanadas").get(0))).containsExactlyInAnyOrder(
                "rifaId", "rifaTitulo", "torneoId", "torneoNombre", "torneoSlug", "puesto", "numeroGanador", "fecha");
        assertThat(perfil.get("insignias")).isNotEmpty();
        for (JsonNode insignia : perfil.get("insignias")) {
            assertThat(claves(insignia)).containsExactlyInAnyOrder(
                    "codigo", "nombre", "descripcion", "icono", "otorgadaEn");
        }
    }

    // --- helpers --------------------------------------------------------------

    private ResultActions perfil(Long usuarioId) throws Exception {
        return mockMvc.perform(get("/api/v1/usuarios/{id}/perfil", usuarioId));
    }

    private JsonNode json(Long usuarioId) throws Exception {
        String cuerpo = perfil(usuarioId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(cuerpo);
    }

    private static List<String> claves(JsonNode objeto) {
        List<String> claves = new ArrayList<>();
        objeto.fieldNames().forEachRemaining(claves::add);
        return claves;
    }

    /** Un torneo gratis por equipos de 2, con la inscripción abierta y el árbitro designado (aceptó). */
    private Long torneoPorEquipos() {
        Long id = torneoService.crear(organizador, new InformacionTorneoRequest("Copa por equipos del perfil", "Futbol",
                null, null, Modalidad.ONLINE, TipoParticipante.EQUIPO, null, null, EN_UN_MES, null,
                Instant.now().plus(7, ChronoUnit.DAYS), "Partidos de 20 minutos", 8, 2, 2, 2)).id();
        arbitrosService.designar(organizador, id,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO' WHERE torneo_id = ?", id);
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 0, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_ABIERTA', publicado_en = now()
                WHERE id = ?""", id);
        entityManager.clear();
        return id;
    }

    /** El capitán inscribe un equipo nuevo con él y el compañero, y confirma: el torneo es gratis. */
    private void inscribirEquipo(Long torneoId, Long capitan, String nombre, Long companiero) {
        Long id = inscripcionService.crear(capitan, torneoId,
                new InscripcionEquipoRequest(null, nombre, List.of(capitan, companiero))).id();
        inscripcionService.confirmar(capitan, id, true);
    }

    /** Cierra la inscripción y genera el bracket. */
    private void cerrarYSortear(Long torneoId) {
        jdbc.update("UPDATE torneo SET estado = 'INSCRIPCION_CERRADA' WHERE id = ?", torneoId);
        entityManager.flush();
        entityManager.clear();
        bracketService.generar(organizador, torneoId);
        entityManager.flush();
        entityManager.clear();
    }

    /** Juegan los capitanes: el que carga gana 1-0, lo acepta el otro. */
    private void jugar(Long partidoId, Long capitanGanador, Long capitanPerdedor) {
        Long capitanLocal = jdbc.queryForObject("""
                SELECT e.capitan_id FROM juega j JOIN inscripcion i ON i.id = j.inscripcion_id
                JOIN equipo e ON e.id = i.equipo_id WHERE j.partido_id = ? AND j.lado = 'LOCAL'""",
                Long.class, partidoId);
        boolean ganaLocal = capitanGanador.equals(capitanLocal);
        resultadoService.cargar(capitanGanador, partidoId,
                new ResultadoRequest(ganaLocal ? 1 : 0, ganaLocal ? 0 : 1, null, null));
        resultadoService.aceptar(capitanPerdedor, partidoId);
        entityManager.flush();
    }

    /**
     * Un torneo de dos que el jugador juega contra un rival nuevo y que termina con la final. Evalúa las insignias como
     * lo hace {@code InsigniaAutomatica} después del commit, que este test no llega a hacer.
     */
    private Long finalizado(Usuario jugador, String nombre, Instant inicio, Instant fin) {
        Usuario rival = escenario.usuario("rival_" + nombre.toLowerCase().replaceAll("\\W", "_"), "PARTICIPANTE");
        Long torneo = escenario.individualEnCurso(organizador, arbitro, nombre, inicio, fin, List.of(jugador, rival));
        Long laFinal = escenario.partido(torneo, 1, 1);
        jugar(laFinal, 1, 0);
        insigniaService.evaluarResultadoConfirmado(torneo, laFinal, true);
        entityManager.flush();
        return torneo;
    }

    /**
     * Una rifa suelta de un premio que el comprador gana con el único número vendido. Evalúa las insignias como lo
     * hace {@code InsigniaAutomatica} después del commit.
     */
    private Long rifaGanada(Long comprador, String titulo, int numero) {
        Long rifa = rifaService.crear(organizador, new RifaRequest(titulo, null, 1, 50, new BigDecimal("500"),
                Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().plus(5, ChronoUnit.DAYS),
                Instant.now().plus(6, ChronoUnit.DAYS), null, null)).id();
        rifaService.reemplazarPremios(organizador, rifa, List.of(new PremioRifaRequest(new BigDecimal("100"),
                "Premio único")));
        rifaService.publicar(organizador, rifa);
        jdbc.update("UPDATE rifa SET venta_desde = now() - interval '1 hour' WHERE id = ?", rifa);
        entityManager.clear();
        ventaRifaService.reservar(comprador, rifa, List.of(numero));
        ventaRifaService.comprar(comprador, rifa, List.of(numero), "compra-" + rifa + "-" + numero);
        entityManager.flush();
        jdbc.update("""
                UPDATE rifa SET venta_desde = now() - interval '3 hours',
                                venta_hasta = now() - interval '2 hours',
                                sorteada_en = now() - interval '1 hour'
                WHERE id = ?""", rifa);
        entityManager.clear();
        cierreVentaService.cerrarSiVencio(rifa, Instant.now().minus(90, ChronoUnit.MINUTES));
        sorteoRifaService.sortear(organizador, rifa);
        entityManager.flush();
        entityManager.clear();
        insigniaService.evaluarRifaSorteada(rifa);
        entityManager.flush();
        return rifa;
    }

    /** El resultado que hace ganar al usuario, juegue de local o de visitante. */
    private int[] ganaElDe(Long partidoId, Long usuarioId) {
        return usuarioId.equals(escenario.jugador(partidoId, "LOCAL")) ? new int[] {1, 0} : new int[] {0, 1};
    }

    private void jugar(Long partidoId, int[] goles) {
        jugar(partidoId, goles[0], goles[1]);
    }

    /** Carga el resultado desde el local y lo acepta el visitante. */
    private void jugar(Long partidoId, int golesLocal, int golesVisitante) {
        resultadoService.cargar(escenario.jugador(partidoId, "LOCAL"), partidoId,
                new ResultadoRequest(golesLocal, golesVisitante, null, null));
        resultadoService.aceptar(escenario.jugador(partidoId, "VISITANTE"), partidoId);
        entityManager.flush();
    }
}
