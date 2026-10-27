package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.Decision;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.dto.EventoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.EvidenciaDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.LaudoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.LaudoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionComparada;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionRequest;
import com.hydra.pica.plataforma_pica.partido.dto.ObjecionResumen;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.dto.VersionResultado;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.TorneoService;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * La vista consolidada de los partidos objetados (HU-303, PICA-246) contra Postgres: el listado del torneo y el
 * comparativo de un partido, con quién los ve. Un torneo individual de 8 con el bracket generado y el árbitro
 * designado; las objeciones se arman cargando, objetando y laudando de verdad. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ObjecionServiceIntegracionTest {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);
    private static final byte[] JPG = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0},
            2048);
    private static final String URL = "http://localhost/api/v1/partidos/1/evidencias";

    @Autowired private ObjecionService objecionService;
    @Autowired private ResultadoService resultadoService;
    @Autowired private EvidenciaService evidenciaService;
    @Autowired private LaudoService laudoService;
    @Autowired private BracketService bracketService;
    @MockitoBean private EmailService emailService;
    @Autowired private TorneoService torneoService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private InscripcionRepository inscripcionRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long arbitro;
    private Long torneo;
    private int jugadores;

    @BeforeEach
    void enCurso() {
        organizador = usuario("org_objeciones", "ORGANIZADOR").getId();
        arbitro = usuario("juez_objeciones", "ARBITRO").getId();
        torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Copa Objeciones", "Ajedrez", null,
                null, Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null, EN_UNA_SEMANA,
                "Partidas de 10 minutos", 16, 2, null, null)).id();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_CERRADA', publicado_en = now()
                WHERE id = ?""", torneo);
        entityManager.clear();
        inscribir(8);
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
    }

    // --- listado del torneo ---------------------------------------------------

    @Test
    void sinSerOrganizadorNiArbitroConPermisoNoSeVenLasObjeciones() {
        Long partido = partido(1);
        objetar(partido);
        Long arbitroAceptadoSinPermiso = arbitro;
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO' WHERE torneo_id = ?", torneo);
        entityManager.clear();

        for (Long sinPermiso : List.of(jugador(partido, "LOCAL"), jugador(partido, "VISITANTE"),
                arbitroAceptadoSinPermiso, usuario("cualquiera_objeciones", "PARTICIPANTE").getId())) {
            assertThatThrownBy(() -> objecionService.delTorneo(sinPermiso, torneo))
                    .extracting("codigo").isEqualTo(CodigoError.SIN_PERMISO);
        }
        assertThat(objecionService.delTorneo(organizador, torneo)).hasSize(1);
        habilitarArbitro();
        assertThat(objecionService.delTorneo(arbitro, torneo)).hasSize(1);
    }

    @Test
    void cadaObjecionTraeLosDosResultadosCargadosYElMotivo() {
        Long partido = partido(1);
        objetar(partido);
        Instant cargadoEn = jdbc.queryForObject("SELECT resultado_cargado_en FROM partido WHERE id = ?",
                Instant.class, partido);

        ObjecionResumen objecion = objecionService.delTorneo(organizador, torneo).getFirst();

        assertThat(objecion).extracting(ObjecionResumen::partidoId, ObjecionResumen::ronda,
                        ObjecionResumen::nombreRonda, ObjecionResumen::local, ObjecionResumen::visitante,
                        ObjecionResumen::motivo, ObjecionResumen::decision, ObjecionResumen::resueltaEn)
                .containsExactly(partido, 1, "Cuartos de final", nombre(partido, "LOCAL"),
                        nombre(partido, "VISITANTE"), "El rival no se presentó al segundo tiempo", null, null);
        assertThat(objecion.cargada()).isEqualTo(new VersionResultado(Lado.LOCAL, nombre(partido, "LOCAL"), 2, 0,
                null, null, cargadoEn, null));
        assertThat(objecion.objetada()).isEqualTo(new VersionResultado(Lado.VISITANTE, nombre(partido, "VISITANTE"),
                0, 1, null, null, objecion.creadaEn(), null));
        assertThat(objecion.creadaEn()).isAfterOrEqualTo(cargadoEn);
    }

    @Test
    void primeroLasPendientesPorAntiguedadYDespuesLasLaudadasDeLaMasReciente() {
        Long laudadaPrimero = partido(1);
        Long pendienteVieja = partido(2);
        Long laudadaDespues = partido(3);
        Long pendienteNueva = partido(4);
        Long fotoDeLaPrimera = objetar(laudadaPrimero);
        objetar(pendienteVieja);
        Long fotoDeLaTercera = objetar(laudadaDespues);
        objetar(pendienteNueva);
        laudar(laudadaPrimero, 2, 0, fotoDeLaPrimera);
        laudar(laudadaDespues, 0, 1, fotoDeLaTercera);

        assertThat(objecionService.delTorneo(organizador, torneo)).extracting(ObjecionResumen::partidoId)
                .containsExactly(pendienteVieja, pendienteNueva, laudadaDespues, laudadaPrimero);
    }

    @Test
    void despuesDeUnLaudoQueCorrigeLaVersionCargadaSigueSiendoLaDelCapitan() {
        Long partido = partido(1);
        laudar(partido, 0, 1, objetar(partido));

        ObjecionResumen objecion = objecionService.delTorneo(organizador, torneo).getFirst();

        assertThat(objecion.decision()).isEqualTo(Decision.CORREGIDO);
        assertThat(objecion.resueltaEn()).isNotNull();
        assertThat(objecion.cargada()).extracting(VersionResultado::puntajeLocal, VersionResultado::puntajeVisitante)
                .containsExactly(2, 0);
    }

    @Test
    void unTorneoSinObjecionesDevuelveLaListaVacia() {
        assertThat(objecionService.delTorneo(organizador, torneo)).isEmpty();
    }

    @Test
    void unTorneoInexistenteEliminadoOEnBorradorNoTieneObjecionesAunqueNoSePuedaResolver() {
        Long borrador = torneoService.crear(organizador, new InformacionTorneoRequest("Copa Borrador", "Ajedrez",
                null, null, Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null,
                EN_UNA_SEMANA, "Partidas de 10 minutos", 16, 2, null, null)).id();
        Long cualquiera = usuario("cualquiera_objeciones", "PARTICIPANTE").getId();

        for (Long inexistente : List.of(999_999L, borrador)) {
            assertThatThrownBy(() -> objecionService.delTorneo(cualquiera, inexistente))
                    .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
        }
        jdbc.update("UPDATE torneo SET eliminado_en = now() WHERE id = ?", torneo);
        entityManager.clear();
        assertThatThrownBy(() -> objecionService.delTorneo(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- comparativo de un partido ----------------------------------------------

    @Test
    void cadaVersionTraeLaEvidenciaQueSubioSuLado() {
        Long partido = partido(1);
        Long fotoDelLocal = evidenciaService.subir(jugador(partido, "LOCAL"), partido, JPG, URL).id();
        Long fotoDelVisitante = objetar(partido);

        ObjecionComparada comparada = objecionService.comparar(organizador, partido);

        assertThat(comparada).extracting(ObjecionComparada::partidoId, ObjecionComparada::torneoId,
                        ObjecionComparada::estado, ObjecionComparada::motivo, ObjecionComparada::laudo)
                .containsExactly(partido, torneo, EstadoPartido.OBJETADO, "El rival no se presentó al segundo tiempo",
                        null);
        assertThat(comparada.cargada()).extracting(VersionResultado::lado, VersionResultado::equipo,
                        VersionResultado::puntajeLocal, VersionResultado::puntajeVisitante)
                .containsExactly(Lado.LOCAL, nombre(partido, "LOCAL"), 2, 0);
        assertThat(comparada.cargada().evidencias()).extracting(EvidenciaDetalle::id, EvidenciaDetalle::lado)
                .containsExactly(tuple(fotoDelLocal, Lado.LOCAL));
        assertThat(comparada.objetada()).extracting(VersionResultado::lado, VersionResultado::equipo,
                        VersionResultado::puntajeLocal, VersionResultado::puntajeVisitante)
                .containsExactly(Lado.VISITANTE, nombre(partido, "VISITANTE"), 0, 1);
        assertThat(comparada.objetada().evidencias()).extracting(EvidenciaDetalle::id, EvidenciaDetalle::lado)
                .containsExactly(tuple(fotoDelVisitante, Lado.VISITANTE));
    }

    @Test
    void unaObjecionLaudadaTraeElLaudoYLaVersionCargadaOriginal() {
        Long partido = partido(1);
        Long foto = objetar(partido);
        laudar(partido, 0, 1, foto);
        String sello = jdbc.queryForObject("SELECT laudo_hash FROM objecion WHERE partido_id = ?", String.class,
                partido);

        ObjecionComparada comparada = objecionService.comparar(organizador, partido);

        assertThat(comparada.estado()).isEqualTo(EstadoPartido.CORREGIDO);
        assertThat(comparada.laudo()).extracting(LaudoDetalle::decision, LaudoDetalle::justificacion,
                        LaudoDetalle::resueltaPor, LaudoDetalle::evidenciaId, LaudoDetalle::laudoHash)
                .containsExactly(Decision.CORREGIDO, "Según la planilla", "org_objeciones Test", foto, sello);
        assertThat(comparada.laudo().resueltaEn()).isNotNull();
        assertThat(comparada.cargada()).extracting(VersionResultado::puntajeLocal, VersionResultado::puntajeVisitante)
                .containsExactly(2, 0);
    }

    @Test
    void unPartidoInexistenteOSinObjecionNoTieneComparativo() {
        Long sinObjecion = partido(2);
        resultadoService.cargar(jugador(sinObjecion, "LOCAL"), sinObjecion, new ResultadoRequest(2, 0, null, null));

        assertThatThrownBy(() -> objecionService.comparar(organizador, 999_999L))
                .extracting("codigo").isEqualTo(CodigoError.PARTIDO_NO_ENCONTRADO);
        assertThatThrownBy(() -> objecionService.comparar(organizador, sinObjecion))
                .extracting("codigo").isEqualTo(CodigoError.OBJECION_NO_ENCONTRADA);
    }

    @Test
    void elComparativoLoVenLosMismosQueElListado() {
        Long partido = partido(1);
        objetar(partido);
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO' WHERE torneo_id = ?", torneo);
        entityManager.clear();

        for (Long sinPermiso : List.of(jugador(partido, "LOCAL"), jugador(partido, "VISITANTE"), arbitro)) {
            assertThatThrownBy(() -> objecionService.comparar(sinPermiso, partido))
                    .extracting("codigo").isEqualTo(CodigoError.SIN_PERMISO);
        }
        habilitarArbitro();
        assertThat(objecionService.comparar(arbitro, partido).partidoId()).isEqualTo(partido);
    }

    @Test
    void elHistorialDiceQuienCargoQuienObjetoYQuienLaudoYCuando() {
        Long partido = partido(1);
        laudar(partido, 0, 1, objetar(partido));
        objetar(partido(2));

        List<EventoPartido> historial = objecionService.comparar(organizador, partido).historial();

        assertThat(historial).extracting(EventoPartido::evento, EventoPartido::usuario, EventoPartido::lado,
                        EventoPartido::equipo, EventoPartido::decision)
                .containsExactly(
                        tuple("CARGADO", nombre(partido, "LOCAL"), Lado.LOCAL, nombre(partido, "LOCAL"), null),
                        tuple("OBJETADO", nombre(partido, "VISITANTE"), Lado.VISITANTE, nombre(partido, "VISITANTE"),
                                null),
                        tuple("LAUDO", "org_objeciones Test", null, null, Decision.CORREGIDO));
        assertThat(historial).extracting(EventoPartido::fecha).isSorted().doesNotContainNull();
    }

    // --- helpers --------------------------------------------------------------

    /** El partido de la primera ronda en esa posición. */
    private Long partido(int posicion) {
        return jdbc.queryForObject("SELECT id FROM partido WHERE torneo_id = ? AND ronda = 1 AND posicion = ?",
                Long.class, torneo, posicion);
    }

    /** El local carga 2-0 y el visitante lo objeta, con una foto, proponiendo 0-1: devuelve la foto. */
    private Long objetar(Long partido) {
        resultadoService.cargar(jugador(partido, "LOCAL"), partido, new ResultadoRequest(2, 0, null, null));
        Long evidencia = evidenciaService.subir(jugador(partido, "VISITANTE"), partido, JPG, URL).id();
        resultadoService.objetar(jugador(partido, "VISITANTE"), partido,
                new ObjecionRequest("El rival no se presentó al segundo tiempo", 0, 1, null, null));
        entityManager.flush();
        entityManager.clear();
        return evidencia;
    }

    private void laudar(Long partido, int local, int visitante, Long evidencia) {
        laudoService.laudar(organizador, partido,
                new LaudoRequest(local, visitante, null, null, "Según la planilla", evidencia));
        entityManager.flush();
        entityManager.clear();
    }

    /** El árbitro del torneo acepta la designación con permiso para resolver objeciones. */
    private void habilitarArbitro() {
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO', resuelve_objeciones = true WHERE torneo_id = ?",
                torneo);
        entityManager.clear();
    }

    private Long jugador(Long partido, String lado) {
        return jdbc.queryForObject("""
                SELECT i.usuario_id FROM juega j JOIN inscripcion i ON i.id = j.inscripcion_id
                WHERE j.partido_id = ? AND j.lado = ?""", Long.class, partido, lado);
    }

    private String nombre(Long partido, String lado) {
        return jdbc.queryForObject("""
                SELECT p.nombres || ' ' || p.apellidos FROM juega j JOIN inscripcion i ON i.id = j.inscripcion_id
                JOIN usuario u ON u.id = i.usuario_id JOIN persona p ON p.id = u.persona_id
                WHERE j.partido_id = ? AND j.lado = ?""", String.class, partido, lado);
    }

    private void inscribir(int cantidad) {
        for (int i = 0; i < cantidad; i++) {
            Inscripcion inscripcion = new Inscripcion();
            inscripcion.setTorneo(torneoRepository.findById(torneo).orElseThrow());
            inscripcion.setUsuario(usuario("jugador_objeciones_" + jugadores++, "PARTICIPANTE"));
            inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
            inscripcion.setReglamentoAceptadoEn(Instant.now());
            inscripcionRepository.saveAndFlush(inscripcion);
        }
    }

    private Usuario usuario(String nombre, String rol) {
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
        usuario = usuarioRepository.save(usuario);
        UsuarioRol asignacion = new UsuarioRol(usuario, rolRepository.findByNombre(rol).orElseThrow());
        asignacion.setAsignadoEn(Instant.now());
        usuarioRolRepository.save(asignacion);
        return usuario;
    }
}
