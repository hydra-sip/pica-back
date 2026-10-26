package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.dto.EvidenciaDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoDetalle;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Cargar el resultado y la evidencia (HU-301, PICA-241) contra Postgres: una prueba por criterio de aceptación, más
 * penales, el próximo cruce y quién ve la evidencia. Un torneo individual de 4 con el bracket generado; cada test hace
 * rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ResultadoServiceIntegracionTest {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);
    private static final byte[] JPG = foto(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 2048);
    private static final String URL = "http://localhost/api/v1/partidos/1/evidencias";

    @Autowired private ResultadoService resultadoService;
    @Autowired private PartidoService partidoService;
    @Autowired private EvidenciaService evidenciaService;
    @Autowired private BracketService bracketService;
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
    /** El primer partido de la primera ronda y los usuarios de cada lado. */
    private Long partido;
    private Long local;
    private Long visitante;

    @BeforeEach
    void enCurso() {
        organizador = usuario("org_resultado", "ORGANIZADOR").getId();
        arbitro = usuario("juez_resultado", "ARBITRO").getId();
        torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Copa Ajedrez", "Ajedrez", null,
                null, Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null, EN_UNA_SEMANA,
                "Partidas de 10 minutos", 16, 2, null, null)).id();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_CERRADA', publicado_en = now()
                WHERE id = ?""", torneo);
        entityManager.clear();
        inscribir(4);
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();

        partido = jdbc.queryForObject("SELECT id FROM partido WHERE torneo_id = ? AND ronda = 1 AND posicion = 1",
                Long.class, torneo);
        local = jugadorDelLado(partido, "LOCAL");
        visitante = jugadorDelLado(partido, "VISITANTE");
    }

    // --- criterios de aceptación ----------------------------------------------

    @Test
    void enUnPartidoInexistenteOQueNoEstaEnJuegoNoSeCarga() {
        assertThatThrownBy(() -> resultadoService.cargar(local, 999_999L, resultado(2, 1)))
                .extracting("codigo").isEqualTo(CodigoError.PARTIDO_NO_ENCONTRADO);

        resultadoService.cargar(local, partido, resultado(2, 1));
        assertThatThrownBy(() -> resultadoService.cargar(visitante, partido, resultado(0, 3)))
                .extracting("codigo").isEqualTo(CodigoError.PARTIDO_NO_EN_JUEGO);
    }

    @Test
    void sinSerCapitanDeAlgunoDeLosDosLadosNoSeCarga() {
        Long deOtroPartido = jugadorDelLado(
                jdbc.queryForObject("SELECT id FROM partido WHERE torneo_id = ? AND ronda = 1 AND posicion = 2",
                        Long.class, torneo), "LOCAL");

        for (Long ajeno : List.of(deOtroPartido, arbitro, organizador)) {
            assertThatThrownBy(() -> resultadoService.cargar(ajeno, partido, resultado(2, 1)))
                    .extracting("codigo").isEqualTo(CodigoError.NO_ES_CAPITAN);
        }
    }

    @Test
    void unResultadoValidoDejaElPartidoPendienteDeConfirmacionSinEvidencia() {
        PartidoDetalle cargado = resultadoService.cargar(visitante, partido, resultado(2, 1));

        assertThat(cargado.estado()).isEqualTo(EstadoPartido.RESULTADO_CARGADO);
        assertThat(cargado.cargadoPor()).isEqualTo(Lado.VISITANTE);
        assertThat(cargado.miLado()).isEqualTo(Lado.VISITANTE);
        assertThat(cargado.local().puntaje()).isEqualTo(2);
        assertThat(cargado.local().ganador()).isTrue();
        assertThat(cargado.visitante().ganador()).isFalse();
        assertThat(Duration.between(cargado.resultadoCargadoEn(), cargado.objecionVenceEn()))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(cargado.resultadoCargadoEn()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        entityManager.flush();
        assertThat(jdbc.queryForMap("""
                SELECT detalle ->> 'resultado' AS resultado, detalle ->> 'lado' AS lado FROM auditoria
                WHERE accion = 'MODIFICACION' AND entidad = 'PARTIDO' AND entidad_id = ?""",
                String.valueOf(partido))).isEqualTo(Map.of("resultado", "CARGADO", "lado", "VISITANTE"));
        assertThat(evidenciaService.listar(local, partido)).isEmpty();
    }

    @Test
    void unArchivoDeOtroFormatoOMasGrandeQueElTopeNoSeAdjunta() {
        byte[] pdf = "%PDF-1.7 planilla".getBytes();
        byte[] fotoEnorme = foto(JPG, EvidenciaService.MAXIMO_IMAGEN + 1);

        assertThatThrownBy(() -> evidenciaService.subir(local, partido, pdf, URL))
                .satisfies(e -> assertThat(errorEnArchivo(e)).isEqualTo(ErrorCampo.Codigo.FORMATO_INVALIDO));
        assertThatThrownBy(() -> evidenciaService.subir(local, partido, fotoEnorme, URL))
                .satisfies(e -> assertThat(errorEnArchivo(e)).isEqualTo(ErrorCampo.Codigo.LONGITUD));
    }

    @Test
    void unaEvidenciaValidaQuedaAsociadaAlLadoDeQuienLaSubio() {
        resultadoService.cargar(local, partido, resultado(3, 1));
        byte[] video = foto(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'}, 10 * 1024 * 1024);

        EvidenciaDetalle foto = evidenciaService.subir(local, partido, JPG, URL);
        evidenciaService.subir(local, partido, video, URL);

        assertThat(foto.url()).isEqualTo(URL + "/" + foto.id() + "/archivo");
        assertThat(foto.hash()).hasSize(64);
        assertThat(evidenciaService.listar(visitante, partido))
                .extracting(EvidenciaDetalle::lado, EvidenciaDetalle::tipoContenido)
                .containsExactly(tuple(Lado.LOCAL, "image/jpeg"),
                        tuple(Lado.LOCAL, "video/mp4"));
        assertThat(evidenciaService.archivo(arbitro, partido, foto.id()).contenido()).isEqualTo(JPG);
    }

    // --- penales --------------------------------------------------------------

    @Test
    void unEmpateSeDefinePorPenalesYSinEmpateNoHayPenales() {
        assertThatThrownBy(() -> resultadoService.cargar(local, partido, resultado(1, 1)))
                .satisfies(e -> assertThat(campos(e)).containsExactly("penalesLocal", "penalesVisitante"));
        assertThatThrownBy(() -> resultadoService.cargar(local, partido, new ResultadoRequest(1, 1, 4, 4)))
                .satisfies(e -> assertThat(campos(e)).containsExactly("penalesVisitante"));
        assertThatThrownBy(() -> resultadoService.cargar(local, partido, new ResultadoRequest(2, 1, 4, null)))
                .satisfies(e -> assertThat(campos(e)).containsExactly("penalesLocal"));

        PartidoDetalle cargado = resultadoService.cargar(local, partido, new ResultadoRequest(1, 1, 3, 4));
        assertThat(cargado.visitante().ganador()).isTrue();
        assertThat(cargado.visitante().penales()).isEqualTo(4);
    }

    // --- próximo cruce y acceso ---------------------------------------------

    @Test
    void miProximoEsElPrimeroSinCerrarYSinJugarNoHay() {
        assertThat(partidoService.miProximo(local, torneo)).get()
                .extracting(PartidoDetalle::id, PartidoDetalle::nombreRonda, PartidoDetalle::miLado)
                .containsExactly(partido, "Semifinal", Lado.LOCAL);
        assertThat(partidoService.miProximo(organizador, torneo)).isEmpty();

        jdbc.update("UPDATE partido SET estado = 'CONFIRMADO' WHERE id = ?", partido);
        entityManager.clear();
        assertThat(partidoService.miProximo(local, torneo)).isEmpty();
    }

    @Test
    void laEvidenciaLaVenLasPartesLosArbitrosYElOrganizadorNoUnTercero() {
        evidenciaService.subir(visitante, partido, JPG, URL);
        Long tercero = usuario("curioso_resultado", "PARTICIPANTE").getId();

        for (Long puede : List.of(local, visitante, arbitro, organizador)) {
            assertThat(evidenciaService.listar(puede, partido)).hasSize(1);
        }
        assertThatThrownBy(() -> evidenciaService.listar(tercero, partido))
                .extracting("codigo").isEqualTo(CodigoError.SIN_PERMISO);
        assertThatThrownBy(() -> evidenciaService.subir(arbitro, partido, JPG, URL))
                .extracting("codigo").isEqualTo(CodigoError.NO_ES_CAPITAN);
    }

    @Test
    void aUnPartidoCerradoNoSeLeSumaEvidencia() {
        jdbc.update("UPDATE partido SET estado = 'CONFIRMADO' WHERE id = ?", partido);
        entityManager.clear();

        assertThatThrownBy(() -> evidenciaService.subir(local, partido, JPG, URL))
                .extracting("codigo").isEqualTo(CodigoError.PARTIDO_CERRADO);
    }

    @Test
    void conElTorneoTerminadoTampocoSeCarga() {
        jdbc.update("UPDATE torneo SET estado = ? WHERE id = ?", EstadoTorneo.FINALIZADO.name(), torneo);
        entityManager.clear();

        assertThatThrownBy(() -> resultadoService.cargar(local, partido, resultado(2, 1)))
                .extracting("codigo").isEqualTo(CodigoError.PARTIDO_NO_EN_JUEGO);
    }

    // --- helpers --------------------------------------------------------------

    private static ResultadoRequest resultado(int local, int visitante) {
        return new ResultadoRequest(local, visitante, null, null);
    }

    /** La firma y después ceros hasta el tamaño pedido. */
    private static byte[] foto(byte[] firma, int tamanio) {
        return Arrays.copyOf(firma, tamanio);
    }

    @SuppressWarnings("unchecked")
    private static List<ErrorCampo> errores(Throwable e) {
        return (List<ErrorCampo>) ((ApiException) e).getPropiedades().get("errores");
    }

    private static ErrorCampo.Codigo errorEnArchivo(Throwable e) {
        ErrorCampo error = errores(e).getFirst();
        assertThat(error.campo()).isEqualTo("archivo");
        return error.codigo();
    }

    private static List<String> campos(Throwable e) {
        return errores(e).stream().map(ErrorCampo::campo).toList();
    }

    private Long jugadorDelLado(Long partidoId, String lado) {
        return jdbc.queryForObject("""
                SELECT i.usuario_id FROM juega j JOIN inscripcion i ON i.id = j.inscripcion_id
                WHERE j.partido_id = ? AND j.lado = ?""", Long.class, partidoId, lado);
    }

    private List<Long> inscribir(int cantidad) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            Inscripcion inscripcion = new Inscripcion();
            inscripcion.setTorneo(torneoRepository.findById(torneo).orElseThrow());
            inscripcion.setUsuario(usuario("jugador_resultado_" + jugadores++, "PARTICIPANTE"));
            inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
            inscripcion.setReglamentoAceptadoEn(Instant.now());
            ids.add(inscripcionRepository.saveAndFlush(inscripcion).getId());
        }
        return ids;
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
