package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignado;
import com.hydra.pica.plataforma_pica.torneo.domain.ArbitroDesignadoId;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.repository.ArbitroDesignadoRepository;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quién arbitra (PICA-237) contra Postgres: solo un designado que aceptó, con el permiso que le dieron, y el que se
 * quita pierde el acceso. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class ArbitrajeIntegracionTest {

    @Autowired private Arbitraje arbitraje;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private TorneoService torneoService;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private ArbitroDesignadoRepository arbitroRepository;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;

    private Long organizador;
    private Long torneo;
    private Long arbitro;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_arbitraje", "ORGANIZADOR").getId();
        arbitro = usuario("juez_arbitraje", "ARBITRO").getId();
        torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null,
                null, null, null, null, null, null, null, null, 16, 2, null, null)).id();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro, Set.of(PermisoArbitro.CARGAR_RESULTADOS)));
    }

    @Test
    void pendienteTodaviaNoArbitra() {
        assertThat(arbitraje.puedeArbitrar(torneo, arbitro, PermisoArbitro.CARGAR_RESULTADOS)).isFalse();
        assertThatThrownBy(() -> arbitraje.validarArbitroDePartido(torneo, arbitro))
                .extracting("codigo").isEqualTo(CodigoError.ARBITRO_NO_HABILITADO);
    }

    @Test
    void aceptadoArbitraSoloConSusPermisos() {
        aceptar();

        assertThat(arbitraje.puedeArbitrar(torneo, arbitro, PermisoArbitro.CARGAR_RESULTADOS)).isTrue();
        assertThat(arbitraje.puedeArbitrar(torneo, arbitro, PermisoArbitro.RESOLVER_OBJECIONES)).isFalse();
        assertThatThrownBy(() -> arbitraje.exigir(torneo, arbitro, PermisoArbitro.RESOLVER_OBJECIONES))
                .extracting("codigo").isEqualTo(CodigoError.SIN_PERMISO);
        arbitraje.validarArbitroDePartido(torneo, arbitro);
    }

    @Test
    void quienNoEstaDesignadoNoArbitra() {
        assertThat(arbitraje.puedeArbitrar(torneo, organizador, PermisoArbitro.CARGAR_RESULTADOS)).isFalse();
        assertThatThrownBy(() -> arbitraje.validarArbitroDePartido(torneo, organizador))
                .extracting("codigo").isEqualTo(CodigoError.ARBITRO_NO_HABILITADO);
    }

    @Test
    void elQueSeQuitaPierdeElAcceso() {
        aceptar();

        arbitrosService.quitar(organizador, torneo, arbitro);

        assertThat(arbitraje.puedeArbitrar(torneo, arbitro, PermisoArbitro.CARGAR_RESULTADOS)).isFalse();
    }

    @Test
    void conPartidosAsignadosNoSeQuita() {
        aceptar();
        Partido partido = new Partido();
        partido.setTorneo(torneoRepository.findById(torneo).orElseThrow());
        partido.setArbitro(usuarioRepository.findById(arbitro).orElseThrow());
        partido.setRonda(1);
        partido.setPosicion(1);
        partidoRepository.saveAndFlush(partido);

        assertThatThrownBy(() -> arbitrosService.quitar(organizador, torneo, arbitro))
                .extracting("codigo").isEqualTo(CodigoError.ARBITRO_CON_PARTIDOS);
        assertThat(arbitraje.puedeArbitrar(torneo, arbitro, PermisoArbitro.CARGAR_RESULTADOS)).isTrue();
    }

    /** Lo que va a hacer el árbitro en HU-807. */
    private void aceptar() {
        ArbitroDesignado designado = arbitroRepository.findById(new ArbitroDesignadoId(torneo, arbitro)).orElseThrow();
        designado.setEstado(EstadoDesignacion.ACEPTADO);
        arbitroRepository.saveAndFlush(designado);
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
