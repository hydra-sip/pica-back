package com.hydra.pica.plataforma_pica;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.partido.service.BracketService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Los datos que arman los tests de integración: usuarios con su rol y un torneo individual en curso con el bracket
 * generado. Se suma con {@code @Import(EscenarioTorneo.class)}; corre dentro de la transacción del test.
 */
@TestComponent
@RequiredArgsConstructor
public class EscenarioTorneo {

    private static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    private static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);

    private final TorneoService torneoService;
    private final ArbitrosTorneoService arbitrosService;
    private final BracketService bracketService;
    private final TorneoRepository torneoRepository;
    private final InscripcionRepository inscripcionRepository;
    private final UsuarioRepository usuarioRepository;
    private final PersonaRepository personaRepository;
    private final RolRepository rolRepository;
    private final UsuarioRolRepository usuarioRolRepository;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    /** Activo y con el mail verificado; se llama "{nombre} Test". */
    public Usuario usuario(String nombre, String rol) {
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

    /**
     * Un torneo individual de ajedrez del organizador, con el árbitro designado para cargar resultados (sin aceptar
     * todavía), {@code jugadores} inscripciones confirmadas de "{prefijo}0", "{prefijo}1"... y el bracket generado:
     * queda EN_CURSO.
     */
    public Long individualEnCurso(Long organizador, Long arbitro, String nombre, String prefijo, int jugadores) {
        Long torneo = torneoService.crear(organizador, new InformacionTorneoRequest(nombre, "Ajedrez", null, null,
                Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null, EN_DOS_SEMANAS, null, EN_UNA_SEMANA,
                "Partidas de 10 minutos", 16, 2, null, null)).id();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = 'INSCRIPCION_CERRADA', publicado_en = now()
                WHERE id = ?""", torneo);
        entityManager.clear();
        for (int i = 0; i < jugadores; i++) {
            Inscripcion inscripcion = new Inscripcion();
            inscripcion.setTorneo(torneoRepository.findById(torneo).orElseThrow());
            inscripcion.setUsuario(usuario(prefijo + i, "PARTICIPANTE"));
            inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
            inscripcion.setReglamentoAceptadoEn(Instant.now());
            inscripcionRepository.saveAndFlush(inscripcion);
        }
        bracketService.generar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
        return torneo;
    }

    /** El torneo empezó ayer, para programar partidos dentro de unos minutos: el escenario lo pone en dos semanas. */
    public void empezadoAyer(Long torneo) {
        jdbc.update("""
                UPDATE torneo SET cierre_inscripcion_en = now() - interval '2 days',
                                  fecha_inicio = now() - interval '1 day' WHERE id = ?""", torneo);
        entityManager.clear();
    }

    public Long partido(Long torneo, int ronda, int posicion) {
        return jdbc.queryForObject("SELECT id FROM partido WHERE torneo_id = ? AND ronda = ? AND posicion = ?",
                Long.class, torneo, ronda, posicion);
    }

    /** El usuario inscripto en ese lado ("LOCAL" o "VISITANTE") del partido. */
    public Long jugador(Long partido, String lado) {
        return jdbc.queryForObject("""
                SELECT i.usuario_id FROM juega j JOIN inscripcion i ON i.id = j.inscripcion_id
                WHERE j.partido_id = ? AND j.lado = ?""", Long.class, partido, lado);
    }

    /** El árbitro del torneo acepta la designación con permiso para resolver objeciones. */
    public void habilitarArbitro(Long torneo) {
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO', resuelve_objeciones = true WHERE torneo_id = ?",
                torneo);
        entityManager.clear();
    }
}
