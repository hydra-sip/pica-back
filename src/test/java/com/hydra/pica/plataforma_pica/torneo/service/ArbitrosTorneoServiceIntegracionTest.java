package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.inscripcion.domain.EstadoInscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.inscripcion.repository.InscripcionRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.dto.ArbitroDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
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
 * Designar árbitros (HU-203, PICA-236) contra Postgres: las pruebas de aceptación de la historia, quién puede ser
 * árbitro y hasta cuándo se cambian. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ArbitrosTorneoServiceIntegracionTest {

    private static final Set<PermisoArbitro> LOS_DOS =
            Set.of(PermisoArbitro.CARGAR_RESULTADOS, PermisoArbitro.RESOLVER_OBJECIONES);

    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private TorneoService torneoService;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private InscripcionRepository inscripcionRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Long organizador;
    private Long torneo;
    private Usuario arbitro;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_arb", "ORGANIZADOR").getId();
        arbitro = usuario("juez_gomez", "ARBITRO");
        torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null,
                null, null, null, null, null, null, null, null, 16, 2, null, null)).id();
    }

    @Test
    void designadoQuedaPendienteConSusPermisos() {
        ArbitroDetalle designado = arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), Set.of(PermisoArbitro.RESOLVER_OBJECIONES)));

        assertThat(designado.estado()).isEqualTo(EstadoDesignacion.PENDIENTE);
        assertThat(designado.permisos()).containsExactly(PermisoArbitro.RESOLVER_OBJECIONES);
        assertThat(arbitrosService.listar(organizador, torneo)).extracting(ArbitroDetalle::username)
                .containsExactly("juez_gomez");
        assertThat(torneoRepository.esArbitro(torneo, arbitro.getId())).isTrue();
    }

    @Test
    void unUsuarioInexistenteNoSeDesigna() {
        assertThatThrownBy(() -> arbitrosService.designar(organizador, torneo, new DesignarArbitroRequest(999_999L, LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_NO_ENCONTRADO);
    }

    @Test
    void elMismoArbitroNoSeDesignaDosVeces() {
        arbitrosService.designar(organizador, torneo, new DesignarArbitroRequest(arbitro.getId(), LOS_DOS));

        assertThatThrownBy(() -> arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.ARBITRO_DUPLICADO);
    }

    @Test
    void soloSeDesignanArbitrosActivos() {
        Usuario jugador = usuario("jugador_arb", "PARTICIPANTE");
        assertThatThrownBy(() -> arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(jugador.getId(), LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_NO_ES_ARBITRO);

        jdbc.update("UPDATE usuario SET estado = 'BLOQUEADO' WHERE id = ?", arbitro.getId());
        entityManager.clear();
        assertThatThrownBy(() -> arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_NO_ES_ARBITRO);
    }

    @Test
    void quienJuegaElTorneoNoLoArbitra() {
        Inscripcion inscripcion = new Inscripcion();
        inscripcion.setTorneo(torneoRepository.findById(torneo).orElseThrow());
        inscripcion.setUsuario(arbitro);
        inscripcion.setEstado(EstadoInscripcion.CONFIRMADA);
        inscripcion.setReglamentoAceptadoEn(Instant.now());
        inscripcionRepository.saveAndFlush(inscripcion);

        assertThatThrownBy(() -> arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.ARBITRO_NO_PERMITIDO);
    }

    @Test
    void quitarloLoSacaDelTorneo() {
        arbitrosService.designar(organizador, torneo, new DesignarArbitroRequest(arbitro.getId(), LOS_DOS));

        arbitrosService.quitar(organizador, torneo, arbitro.getId());

        assertThat(arbitrosService.listar(organizador, torneo)).isEmpty();
        assertThat(torneoRepository.esArbitro(torneo, arbitro.getId())).isFalse();
        assertThatThrownBy(() -> arbitrosService.quitar(organizador, torneo, arbitro.getId()))
                .extracting("codigo").isEqualTo(CodigoError.ARBITRO_NO_ENCONTRADO);
    }

    @Test
    void seCambianHastaQueElTorneoTermina() {
        // fuera de BORRADOR la base exige el torneo completo (V18)
        jdbc.update("""
                UPDATE torneo SET estado = 'CANCELADO', motivo_cancelacion = 'lluvia', modalidad = 'ONLINE',
                    tipo_participante = 'INDIVIDUAL', valor_inscripcion = 0, pct_pozo_competidores = 90,
                    pct_premio_rifa = 0, fecha_inicio = now(), cierre_inscripcion_en = now(),
                    reglamento_texto = 'Reglas'
                WHERE id = ?""", torneo);
        entityManager.clear();

        assertThatThrownBy(() -> arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void elTorneoDeOtroNoExiste() {
        Long otro = usuario("otro_arb", "ORGANIZADOR").getId();

        assertThatThrownBy(() -> arbitrosService.listar(otro, torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
        assertThatThrownBy(() -> arbitrosService.designar(otro, torneo,
                new DesignarArbitroRequest(arbitro.getId(), LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_ENCONTRADO);
    }

    @Test
    void laBusquedaTraeSoloArbitrosActivos() {
        usuario("juez_perez", "ARBITRO");
        usuario("juez_jugador", "PARTICIPANTE");

        List<String> encontrados = arbitrosService.buscar("juez", 0, 20).map(UsuarioPublico::username).getContent();

        assertThat(encontrados).contains("juez_gomez", "juez_perez").doesNotContain("juez_jugador");
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
