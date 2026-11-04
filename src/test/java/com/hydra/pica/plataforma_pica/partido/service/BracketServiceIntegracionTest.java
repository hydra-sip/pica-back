package com.hydra.pica.plataforma_pica.partido.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.dto.BracketTorneo;
import com.hydra.pica.plataforma_pica.partido.dto.PartidoBracket;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
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
 * Generar y regenerar el bracket (HU-207, PICA-215) contra Postgres: una prueba por criterio de aceptación, más
 * árbitros y regenerar. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class BracketServiceIntegracionTest {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);

    @Autowired private BracketService bracketService;
    @Autowired private TorneoService torneoService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private ArbitroDesignadoRepository arbitroRepository;
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
    void cerrado() {
        organizador = usuario("org_bracket", "ORGANIZADOR").getId();
        arbitro = usuario("juez_bracket", "ARBITRO").getId();
        torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura Ajedrez", "Ajedrez", null,
                null, Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null, EN_UNA_SEMANA,
                "Partidas de 10 minutos", 16, 2, null, null)).id();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        estado(EstadoTorneo.INSCRIPCION_CERRADA);
    }

    // --- criterios de aceptación ----------------------------------------------

    @Test
    void conLaInscripcionAbiertaNoSeGenera() {
        inscribir(4);
        estado(EstadoTorneo.INSCRIPCION_ABIERTA);

        assertThatThrownBy(() -> bracketService.generar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.INSCRIPCION_NO_CERRADA);
    }

    @Test
    void conMenosDeDosConfirmadosNoSeGenera() {
        inscribir(1);
        inscribir(1, EstadoInscripcion.PENDIENTE_PAGO);

        assertThatThrownBy(() -> bracketService.generar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.PARTICIPANTES_INSUFICIENTES);
    }

    @Test
    void unaSegundaVezNoSeGenera() {
        inscribir(4);
        bracketService.generar(organizador, torneo);

        assertThatThrownBy(() -> bracketService.generar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.BRACKET_YA_GENERADO);
    }

    @Test
    void cadaInscriptoApareceUnaVezEnLaPrimeraRondaYElTorneoEmpieza() {
        List<Long> inscriptos = inscribir(5);

        BracketTorneo bracket = bracketService.generar(organizador, torneo);

        assertThat(ladosDeLaRonda(bracket, 1)).containsExactlyInAnyOrderElementsOf(inscriptos);
        assertThat(bracket.semilla()).hasSize(64);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT estado FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo("EN_CURSO");
        assertThat(jdbc.queryForObject("SELECT semilla_bracket FROM torneo WHERE id = ?", String.class, torneo))
                .isEqualTo(bracket.semilla());
        assertThat(jdbc.queryForObject("""
                SELECT detalle ->> 'semilla' FROM auditoria
                WHERE accion = 'SORTEO' AND entidad = 'TORNEO' AND entidad_id = ?""", String.class,
                String.valueOf(torneo))).isEqualTo(bracket.semilla());
    }

    @Test
    void conOchoNoHayByeYEstanTodasLasRondas() {
        inscribir(8);

        BracketTorneo bracket = bracketService.generar(organizador, torneo);

        assertThat(bracket.rondas()).extracting(RondaBracket::nombre)
                .containsExactly("Cuartos de final", "Semifinal", "Final");
        assertThat(bracket.rondas()).extracting(r -> r.partidos().size()).containsExactly(4, 2, 1);
        assertThat(bracket.rondas().get(0).partidos()).noneMatch(PartidoBracket::bye);
        // las rondas siguientes esperan a que avance la llave
        assertThat(bracket.rondas().get(1).partidos()).allMatch(p -> p.local() == null && p.visitante() == null);
    }

    @Test
    void conOnceHayCincoByeSoloEnLaPrimeraRondaYAvanzanSinJugar() {
        inscribir(11);

        BracketTorneo bracket = bracketService.generar(organizador, torneo);

        List<PartidoBracket> primera = bracket.rondas().get(0).partidos();
        List<PartidoBracket> byes = primera.stream().filter(PartidoBracket::bye).toList();
        assertThat(primera).hasSize(8);
        assertThat(byes).hasSize(5)
                .allMatch(p -> p.estado() == EstadoPartido.CONFIRMADO && Boolean.TRUE.equals(p.local().ganador()));
        assertThat(bracket.rondas().stream().skip(1).flatMap(r -> r.partidos().stream())).noneMatch(PartidoBracket::bye);
        assertThat(ladosDeLaRonda(bracket, 2))
                .containsExactlyInAnyOrderElementsOf(byes.stream().map(p -> p.local().inscripcionId()).toList());
    }

    // --- árbitros -------------------------------------------------------------

    @Test
    void cadaPartidoTieneUnArbitroYSinArbitrosNoSeGenera() {
        inscribir(4);
        ArbitroDesignado designado = arbitroRepository.findById(new ArbitroDesignadoId(torneo, arbitro)).orElseThrow();
        designado.setEstado(EstadoDesignacion.RECHAZADO);
        arbitroRepository.saveAndFlush(designado);

        assertThatThrownBy(() -> bracketService.generar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.SIN_ARBITROS);

        designado.setEstado(EstadoDesignacion.PENDIENTE);
        arbitroRepository.saveAndFlush(designado);
        BracketTorneo bracket = bracketService.generar(organizador, torneo);
        assertThat(bracket.rondas().stream().flatMap(r -> r.partidos().stream()))
                .allMatch(p -> "juez_bracket Test".equals(p.arbitro()));
    }

    @Test
    void siAlgunoAceptoLosPartidosVanALosQueAceptaron() {
        inscribir(4);
        Long otro = usuario("juez2_bracket", "ARBITRO").getId();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(otro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        ArbitroDesignado acepto = arbitroRepository.findById(new ArbitroDesignadoId(torneo, otro)).orElseThrow();
        acepto.setEstado(EstadoDesignacion.ACEPTADO);
        arbitroRepository.saveAndFlush(acepto);

        BracketTorneo bracket = bracketService.generar(organizador, torneo);

        assertThat(bracket.rondas().stream().flatMap(r -> r.partidos().stream()))
                .allMatch(p -> "juez2_bracket Test".equals(p.arbitro()));
    }

    // --- regenerar ------------------------------------------------------------

    @Test
    void regenerarSorteaDeNuevoYDejaLaSemillaAnteriorEnLaAuditoria() {
        inscribir(6);
        String primera = bracketService.generar(organizador, torneo).semilla();

        BracketTorneo otro = bracketService.regenerar(organizador, torneo);

        assertThat(otro.semilla()).isNotEqualTo(primera);
        assertThat(otro.rondas()).extracting(r -> r.partidos().size()).containsExactly(4, 2, 1);
        entityManager.flush();
        assertThat(jdbc.queryForObject("""
                SELECT detalle ->> 'semillaAnterior' FROM auditoria
                WHERE accion = 'SORTEO' AND entidad_id = ? AND detalle ->> 'bracket' = 'REGENERADO'""",
                String.class, String.valueOf(torneo))).isEqualTo(primera);
    }

    @Test
    void conUnResultadoCargadoNoSeRegenera() {
        inscribir(4);
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        jdbc.update("""
                UPDATE juega SET puntaje = 2
                WHERE partido_id = (SELECT id FROM partido WHERE torneo_id = ? AND ronda = 1 AND posicion = 1)
                  AND lado = 'LOCAL'""", torneo);

        assertThatThrownBy(() -> bracketService.regenerar(organizador, torneo))
                .extracting("codigo").isEqualTo(CodigoError.BRACKET_CON_RESULTADOS);
    }

    @Test
    void elDeOtroOrganizadorNoExiste() {
        inscribir(4);
        Long otro = usuario("otro_bracket", "ORGANIZADOR").getId();

        assertThatThrownBy(() -> bracketService.generar(otro, torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- ver (PICA-216) --------------------------------------------------------

    @Test
    void cualquieraLoVeIgualQueElOrganizadorYSinGenerarVieneVacio() {
        inscribir(5);
        assertThat(bracketService.ver(torneo).rondas()).isEmpty();

        BracketTorneo generado = bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();

        assertThat(bracketService.ver(torneo)).isEqualTo(generado);
    }

    @Test
    void elDeUnBorradorNoSeVe() {
        jdbc.update("UPDATE torneo SET estado = 'BORRADOR' WHERE id = ?", torneo);
        entityManager.clear();

        assertThatThrownBy(() -> bracketService.ver(torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    // --- helpers --------------------------------------------------------------

    /** Las inscripciones que juegan en la ronda, sin repetir ninguna. */
    private static List<Long> ladosDeLaRonda(BracketTorneo bracket, int ronda) {
        List<Long> ids = new ArrayList<>();
        bracket.rondas().get(ronda - 1).partidos().forEach(p -> {
            ids.add(p.local() == null ? null : p.local().inscripcionId());
            ids.add(p.visitante() == null ? null : p.visitante().inscripcionId());
        });
        List<Long> sinVacios = ids.stream().filter(Objects::nonNull).toList();
        assertThat(sinVacios).doesNotHaveDuplicates();
        return sinVacios;
    }

    private void estado(EstadoTorneo estado) {
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = ?, publicado_en = now()
                WHERE id = ?""", estado.name(), torneo);
        entityManager.clear();
    }

    private List<Long> inscribir(int cantidad) {
        return inscribir(cantidad, EstadoInscripcion.CONFIRMADA);
    }

    private List<Long> inscribir(int cantidad, EstadoInscripcion estado) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            Inscripcion inscripcion = new Inscripcion();
            inscripcion.setTorneo(torneoRepository.findById(torneo).orElseThrow());
            inscripcion.setUsuario(usuario("jugador_bracket_" + jugadores++, "PARTICIPANTE"));
            inscripcion.setEstado(estado);
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
