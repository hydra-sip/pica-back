package com.hydra.pica.plataforma_pica.inscripcion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.dto.InscripcionResumen;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mis inscripciones (PICA-234) contra Postgres: las que armó el usuario (la individual, o las de sus equipos si
 * es capitán), en todos los estados, la más nueva primero y filtrables por torneo. Cada test hace rollback al
 * terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class InscripcionMiasServiceIntegracionTest {

    private static final Pageable PRIMERA_PAGINA = PageRequest.of(0, 20);

    @Autowired private InscripcionService inscripcionService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long organizador;
    private Long jugador;
    private Long torneoIndividual;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_mias").getId();
        jugador = usuario("jug_mias").getId();
        torneoIndividual = torneoIndividual();
    }

    @Test
    void traeLaInscripcionIndividualDelParticipanteSinEquipo() {
        long borrador = inscripcionIndividual(torneoIndividual, jugador, "BORRADOR");

        Page<InscripcionResumen> mias = inscripcionService.listarMias(jugador, null, PRIMERA_PAGINA);

        assertThat(mias.getContent()).hasSize(1);
        InscripcionResumen resumen = mias.getContent().get(0);
        assertThat(resumen.id()).isEqualTo(borrador);
        assertThat(resumen.torneoId()).isEqualTo(torneoIndividual);
        assertThat(resumen.usuarioId()).isEqualTo(jugador);
        assertThat(resumen.estado()).isEqualTo(EstadoInscripcion.BORRADOR);
        assertThat(resumen.equipo()).isNull();
        assertThat(resumen.creadoEn()).isNotNull();
        assertThat(resumen.modificadoEn()).isNotNull();
    }

    @Test
    void cadaInscripcionTraeSuTorneoConNombreYSlug() {
        jdbc.update("UPDATE torneo SET slug = ? WHERE id = ?", "torneo-mias", torneoIndividual);
        inscripcionIndividual(torneoIndividual, jugador, "BORRADOR");

        InscripcionResumen resumen = inscripcionService.listarMias(jugador, null, PRIMERA_PAGINA).getContent().get(0);

        assertThat(resumen.torneo().id()).isEqualTo(torneoIndividual);
        assertThat(resumen.torneo().nombre())
                .isEqualTo(jdbc.queryForObject("SELECT nombre FROM torneo WHERE id = ?", String.class, torneoIndividual));
        assertThat(resumen.torneo().slug()).isEqualTo("torneo-mias");
    }

    @Test
    void traeLasInscripcionesDeLosEquiposDelCapitanConSuEquipoYSinUsuario() {
        Long capitan = usuario("cap_mias").getId();
        long halcones = equipo("Los Halcones", capitan);
        long inscripcion = inscripcionDeEquipo(torneoPorEquipos(), halcones, "PENDIENTE_PAGO");

        Page<InscripcionResumen> mias = inscripcionService.listarMias(capitan, null, PRIMERA_PAGINA);

        assertThat(mias.getContent()).hasSize(1);
        InscripcionResumen resumen = mias.getContent().get(0);
        assertThat(resumen.id()).isEqualTo(inscripcion);
        assertThat(resumen.estado()).isEqualTo(EstadoInscripcion.PENDIENTE_PAGO);
        assertThat(resumen.usuarioId()).isNull();
        assertThat(resumen.equipo().id()).isEqualTo(halcones);
        assertThat(resumen.equipo().nombre()).isEqualTo("Los Halcones");
    }

    @Test
    void noTraeLasAjenasNiLasDeUnEquipoDondeSoloFiguroEnLaNomina() {
        Long capitan = usuario("cap_ajeno_mias").getId();
        long halcones = equipo("Los Halcones", capitan);
        integrante(halcones, jugador);
        long deEquipo = inscripcionDeEquipo(torneoPorEquipos(), halcones, "PENDIENTE_PAGO");
        enNomina(deEquipo, capitan, halcones);
        enNomina(deEquipo, jugador, halcones);
        inscripcionIndividual(torneoIndividual, usuario("otro_mias").getId(), "BORRADOR");

        assertThat(inscripcionService.listarMias(jugador, null, PRIMERA_PAGINA).getContent()).isEmpty();
    }

    @Test
    void traeTodosLosEstadosConLaMasNuevaPrimero() {
        String[] estados = {"BORRADOR", "PENDIENTE_PAGO", "CONFIRMADA", "CANCELADA", "REINTEGRADA"};
        for (String estado : estados) {
            inscripcionIndividual(torneoIndividual(), jugador, estado);
        }

        Page<InscripcionResumen> mias = inscripcionService.listarMias(jugador, null, PRIMERA_PAGINA);

        assertThat(mias.getContent()).extracting(InscripcionResumen::estado).containsExactly(
                EstadoInscripcion.REINTEGRADA, EstadoInscripcion.CANCELADA, EstadoInscripcion.CONFIRMADA,
                EstadoInscripcion.PENDIENTE_PAGO, EstadoInscripcion.BORRADOR);
    }

    @Test
    void conTorneoIdSoloTraeLasDeEseTorneo() {
        Long capitan = usuario("cap_filtro_mias").getId();
        long halcones = equipo("Los Halcones", capitan);
        long otroTorneo = torneoPorEquipos();
        inscripcionDeEquipo(torneoPorEquipos(), halcones, "CANCELADA");
        long buscada = inscripcionDeEquipo(otroTorneo, equipo("Los Zorros", capitan), "BORRADOR");

        Page<InscripcionResumen> mias = inscripcionService.listarMias(capitan, otroTorneo, PRIMERA_PAGINA);

        assertThat(mias.getContent()).extracting(InscripcionResumen::id).containsExactly(buscada);
        assertThat(mias.getTotalElements()).isEqualTo(1);
    }

    @Test
    void paginaYIgnoraElOrdenPedido() {
        long primera = inscripcionIndividual(torneoIndividual(), jugador, "CONFIRMADA");
        long segunda = inscripcionIndividual(torneoIndividual(), jugador, "BORRADOR");
        long tercera = inscripcionIndividual(torneoIndividual(), jugador, "CANCELADA");
        Pageable pedido = PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "estado"));

        Page<InscripcionResumen> pagina = inscripcionService.listarMias(jugador, null, pedido);
        Page<InscripcionResumen> siguiente = inscripcionService.listarMias(jugador, null, pedido.next());

        assertThat(pagina.getContent()).extracting(InscripcionResumen::id).containsExactly(tercera, segunda);
        assertThat(pagina.getTotalElements()).isEqualTo(3);
        assertThat(pagina.getTotalPages()).isEqualTo(2);
        assertThat(siguiente.getContent()).extracting(InscripcionResumen::id).containsExactly(primera);
    }

    // --- escenario ----------------------------------------------------------

    private long inscripcionIndividual(long torneoId, long usuario, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, usuario_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, CASE WHEN ? = 'BORRADOR' THEN NULL ELSE now() END)
                RETURNING id
                """, Long.class, torneoId, usuario, estado, estado);
    }

    private long torneoPorEquipos() {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, tamanio_equipo, nomina_maxima,
                                    valor_inscripcion, pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en,
                                    fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Futbol', 'ELIMINACION_DIRECTA', 'PRESENCIAL', 'EQUIPO', 8, 2, 5, 8,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
    }

    private long equipo(String nombre, Long capitanId) {
        long id = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, ?) RETURNING id", Long.class, capitanId, nombre);
        integrante(id, capitanId);
        return id;
    }

    private void integrante(long equipoId, Long usuarioId) {
        jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?)", usuarioId, equipoId);
    }

    private long inscripcionDeEquipo(long torneoId, long equipoId, String estado) {
        return jdbc.queryForObject("""
                INSERT INTO inscripcion (torneo_id, equipo_id, estado, reglamento_aceptado_en)
                VALUES (?, ?, ?, CASE WHEN ? = 'BORRADOR' THEN NULL ELSE now() END)
                RETURNING id
                """, Long.class, torneoId, equipoId, estado, estado);
    }

    private void enNomina(long inscripcionId, Long usuarioId, long equipoId) {
        jdbc.update("INSERT INTO nomina (inscripcion_id, usuario_id, equipo_id) VALUES (?, ?, ?)", inscripcionId, usuarioId, equipoId);
    }

    private long torneoIndividual() {
        return jdbc.queryForObject("""
                INSERT INTO torneo (organizador_id, nombre, reglamento_url, disciplina, formato, modalidad,
                                    tipo_participante, cupo_maximo, cupo_minimo, valor_inscripcion,
                                    pct_pozo_competidores, pct_premio_rifa, cierre_inscripcion_en, fecha_inicio, estado)
                VALUES (?, 'Torneo', 'https://r', 'Ajedrez', 'ELIMINACION_DIRECTA', 'ONLINE', 'INDIVIDUAL', 8, 2,
                        1000, 50, 10, now() + interval '1 day', now() + interval '7 days', 'INSCRIPCION_ABIERTA')
                RETURNING id
                """, Long.class, organizador);
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
