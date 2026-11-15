package com.hydra.pica.plataforma_pica.partido.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.rifa.service.Drand;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.domain.ReglasGrupos;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.torneo.service.ArbitrosTorneoService;
import com.hydra.pica.plataforma_pica.torneo.service.InscripcionTorneoService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Un torneo con fase de grupos listo para sortear (CR-02): organizador, un árbitro que aceptó y la inscripción
 * abierta. Las subclases llevan {@code @SpringBootTest}, el Postgres de Testcontainers y {@code @Transactional}.
 * drand no sale a internet: la ronda ya salió y su valor es el SHA-256 de {@code "ronda:<n>"}.
 */
abstract class TorneoConGruposBase {

    static final Instant EN_UNA_SEMANA = Instant.now().plus(7, ChronoUnit.DAYS);
    static final Instant EN_DOS_SEMANAS = Instant.now().plus(14, ChronoUnit.DAYS);

    @Autowired protected TorneoService torneoService;
    @Autowired protected ArbitrosTorneoService arbitrosService;
    @Autowired protected InscripcionTorneoService inscripcionTorneoService;
    @Autowired protected BracketService bracketService;
    @Autowired protected TorneoRepository torneoRepository;
    @Autowired protected InscripcionRepository inscripcionRepository;
    @Autowired protected UsuarioRepository usuarioRepository;
    @Autowired protected PersonaRepository personaRepository;
    @Autowired protected RolRepository rolRepository;
    @Autowired protected UsuarioRolRepository usuarioRolRepository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected EntityManager entityManager;

    @MockitoSpyBean protected Drand drand;

    protected Long organizador;
    protected Long arbitro;
    protected Long torneo;
    private int jugadores;

    @BeforeEach
    void torneoConGrupos() {
        doAnswer(invocacion -> {
            long ronda = invocacion.getArgument(0);
            return new Drand.Pulso(ronda, valorDeLaRonda(ronda), "firma-" + ronda);
        }).when(drand).pulso(anyLong());
        doReturn(true).when(drand).salio(anyLong());

        organizador = usuario("org_grupos", "ORGANIZADOR").getId();
        arbitro = usuario("juez_grupos", "ARBITRO").getId();
        torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Copa con grupos", "Ajedrez", null,
                Formato.GRUPOS_Y_ELIMINACION, reglas(), Modalidad.ONLINE, TipoParticipante.INDIVIDUAL, null, null,
                EN_DOS_SEMANAS, null, EN_UNA_SEMANA, "Partidas de 10 minutos", cupoMaximo(), cupoMinimo(), null, null,
                Map.of())).id();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
        jdbc.update("UPDATE torneo_arbitro SET estado = 'ACEPTADO' WHERE torneo_id = ?", torneo);
        estado(EstadoTorneo.INSCRIPCION_ABIERTA);
    }

    /** Por defecto, las de Fede: grupos de 4, pasan 2, 3 y 1 puntos. */
    protected ReglasGrupos reglas() {
        return ReglasGrupos.POR_DEFECTO;
    }

    /** Por defecto, 2 grupos de 4. */
    protected int cupoMaximo() {
        return 8;
    }

    protected int cupoMinimo() {
        return 6;
    }

    static String valorDeLaRonda(long ronda) {
        return SelloTorneo.sha256("ronda:" + ronda);
    }

    /** Cierra la inscripción como el organizador: sella la lista y fija la ronda de drand. */
    protected void cerrar() {
        inscripcionTorneoService.cerrar(organizador, torneo);
        entityManager.flush();
        entityManager.clear();
    }

    protected void estado(EstadoTorneo estado) {
        jdbc.update("""
                UPDATE torneo SET valor_inscripcion = 1000, pct_pozo_competidores = 90, pct_premio_rifa = 0,
                                  estado = ?, publicado_en = now()
                WHERE id = ?""", estado.name(), torneo);
        entityManager.clear();
    }

    protected List<Long> inscribir(int cantidad) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            Inscripcion inscripcion = new Inscripcion();
            inscripcion.setTorneo(torneoRepository.findById(torneo).orElseThrow());
            inscripcion.setUsuario(usuario("jugador_grupos_" + jugadores++, "PARTICIPANTE"));
            inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
            inscripcion.setReglamentoAceptadoEn(Instant.now());
            ids.add(inscripcionRepository.saveAndFlush(inscripcion).getId());
        }
        return ids;
    }

    protected Usuario usuario(String nombre, String rol) {
        Persona persona = new Persona();
        persona.setNombres(nombre);
        persona.setApellidos("Test");
        persona.setEstado(EstadoGeneral.ACTIVO);
        if (rol.equals("ARBITRO")) {
            // el torneo cobra inscripción: para arbitrarlo hace falta la identidad verificada (HU-604)
            persona.setTipoDoc("DNI");
            persona.setNroDoc(String.valueOf(32_000_000 + Math.abs(nombre.hashCode() % 1_000_000)));
            persona.setFechaNacimiento(LocalDate.of(1990, 1, 1));
        }
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
