package com.hydra.pica.plataforma_pica.torneo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Set;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoDesignacion;
import com.hydra.pica.plataforma_pica.torneo.domain.PermisoArbitro;
import com.hydra.pica.plataforma_pica.torneo.dto.ArbitroDetalle;
import com.hydra.pica.plataforma_pica.torneo.dto.DesignarArbitroRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.InformacionTorneoRequest;
import com.hydra.pica.plataforma_pica.torneo.dto.MiDesignacion;
import com.hydra.pica.plataforma_pica.torneo.event.ArbitroDesignadoEnTorneo;
import com.hydra.pica.plataforma_pica.torneo.event.DesignacionRespondida;
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
 * Aceptar y rechazar la designación (HU-807, PICA-313) contra Postgres: las pruebas de aceptación de la historia.
 * Los mails van después del commit, que en los tests no llega: el aviso se llama a mano. Cada test hace rollback.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class DesignacionesServiceIntegracionTest {

    private static final Set<PermisoArbitro> LOS_DOS =
            Set.of(PermisoArbitro.CARGAR_RESULTADOS, PermisoArbitro.RESOLVER_OBJECIONES);

    @Autowired private DesignacionesService designacionesService;
    @Autowired private ArbitrosTorneoService arbitrosService;
    @Autowired private TorneoService torneoService;
    @Autowired private Arbitraje arbitraje;
    @Autowired private AvisoDesignacion avisoDesignacion;
    @Autowired private TorneoRepository torneoRepository;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    @MockitoBean private EmailService emailService;

    private Long organizador;
    private Long torneo;
    private Usuario arbitro;

    @BeforeEach
    void escenario() {
        organizador = usuario("org_desig", "ORGANIZADOR").getId();
        arbitro = usuario("juez_desig", "ARBITRO");
        torneo = torneoService.crear(organizador, new InformacionTorneoRequest("Apertura F5", "Fútbol 5", null, null,
                null, null, null, null, null, null, null, null, 16, 2, null, null)).id();
        arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), Set.of(PermisoArbitro.RESOLVER_OBJECIONES)));
    }

    @Test
    void elArbitroVeLaDesignacionConElTorneoYLosPermisosPropuestos() {
        assertThat(designacionesService.mias(arbitro.getId())).singleElement().satisfies(d -> {
            assertThat(d.torneoId()).isEqualTo(torneo);
            assertThat(d.torneo()).isEqualTo("Apertura F5");
            assertThat(d.organizador()).isEqualTo("org_desig Test");
            assertThat(d.estado()).isEqualTo(EstadoDesignacion.PENDIENTE);
            assertThat(d.permisos()).containsExactly(PermisoArbitro.RESOLVER_OBJECIONES);
        });
        assertThat(designacionesService.mias(organizador)).isEmpty();
    }

    @Test
    void lasPendientesVanPrimero() {
        Long otro = torneoService.crear(organizador, new InformacionTorneoRequest("Clausura F5", "Fútbol 5", null,
                null, null, null, null, null, null, null, null, null, 16, 2, null, null)).id();
        arbitrosService.designar(organizador, otro, new DesignarArbitroRequest(arbitro.getId(), LOS_DOS));
        designacionesService.aceptar(arbitro.getId(), otro);

        assertThat(designacionesService.mias(arbitro.getId())).extracting(MiDesignacion::estado)
                .containsExactly(EstadoDesignacion.PENDIENTE, EstadoDesignacion.ACEPTADO);
    }

    @Test
    void alDesignarLeLlegaUnMailConElTorneoYLosPermisos() {
        avisoDesignacion.avisarDesignacion(new ArbitroDesignadoEnTorneo(torneo, arbitro.getId()));

        verify(emailService).enviarAviso(eq("juez_desig@example.com"), eq("Te designaron árbitro de Apertura F5"),
                contains("Permisos propuestos: resolver objeciones."));
    }

    @Test
    void antesDeAceptarNoArbitra() {
        assertThat(arbitraje.puedeArbitrar(torneo, arbitro.getId(), PermisoArbitro.RESOLVER_OBJECIONES)).isFalse();
        assertThatThrownBy(() -> arbitraje.exigir(torneo, arbitro.getId(), PermisoArbitro.RESOLVER_OBJECIONES))
                .extracting("codigo").isEqualTo(CodigoError.SIN_PERMISO);
    }

    @Test
    void alAceptarArbitraConLosPermisosDefinidosYSeLeAvisaAlOrganizador() {
        MiDesignacion aceptada = designacionesService.aceptar(arbitro.getId(), torneo);

        assertThat(aceptada.estado()).isEqualTo(EstadoDesignacion.ACEPTADO);
        assertThat(arbitraje.puedeArbitrar(torneo, arbitro.getId(), PermisoArbitro.RESOLVER_OBJECIONES)).isTrue();
        assertThat(arbitraje.puedeArbitrar(torneo, arbitro.getId(), PermisoArbitro.CARGAR_RESULTADOS)).isFalse();

        avisoDesignacion.avisarRespuesta(new DesignacionRespondida(torneo, arbitro.getId(), EstadoDesignacion.ACEPTADO));
        verify(emailService).enviarAviso(eq("org_desig@example.com"),
                eq("juez_desig Test aceptó ser árbitro de Apertura F5"), contains("resolver objeciones"));
    }

    @Test
    void alRechazarNoQuedaComoArbitroYSeLeAvisaAlOrganizador() {
        MiDesignacion rechazada = designacionesService.rechazar(arbitro.getId(), torneo);

        assertThat(rechazada.estado()).isEqualTo(EstadoDesignacion.RECHAZADO);
        assertThat(torneoRepository.esArbitro(torneo, arbitro.getId())).isFalse();
        assertThat(arbitraje.puedeArbitrar(torneo, arbitro.getId(), PermisoArbitro.RESOLVER_OBJECIONES)).isFalse();
        assertThat(arbitrosService.listar(organizador, torneo)).extracting(ArbitroDetalle::estado)
                .containsExactly(EstadoDesignacion.RECHAZADO);

        avisoDesignacion.avisarRespuesta(new DesignacionRespondida(torneo, arbitro.getId(), EstadoDesignacion.RECHAZADO));
        verify(emailService).enviarAviso(eq("org_desig@example.com"),
                eq("juez_desig Test rechazó ser árbitro de Apertura F5"), contains("designar a otro"));
    }

    @Test
    void unaDesignacionSeRespondeUnaSolaVez() {
        designacionesService.aceptar(arbitro.getId(), torneo);

        assertThatThrownBy(() -> designacionesService.rechazar(arbitro.getId(), torneo))
                .extracting("codigo").isEqualTo(CodigoError.DESIGNACION_YA_RESPONDIDA);
        assertThatThrownBy(() -> designacionesService.aceptar(arbitro.getId(), torneo))
                .extracting("codigo").isEqualTo(CodigoError.DESIGNACION_YA_RESPONDIDA);
    }

    @Test
    void sinDesignacionEs404() {
        Usuario otro = usuario("juez_otro", "ARBITRO");

        assertThatThrownBy(() -> designacionesService.aceptar(otro.getId(), torneo))
                .extracting("codigo").isEqualTo(CodigoError.DESIGNACION_NO_ENCONTRADA);
        assertThatThrownBy(() -> designacionesService.rechazar(arbitro.getId(), 999_999L))
                .extracting("codigo").isEqualTo(CodigoError.DESIGNACION_NO_ENCONTRADA);
    }

    @Test
    void conElTorneoTerminadoNoSeResponde() {
        // fuera de BORRADOR la base exige el torneo completo (V18)
        jdbc.update("""
                UPDATE torneo SET estado = 'CANCELADO', motivo_cancelacion = 'lluvia', modalidad = 'ONLINE',
                    tipo_participante = 'INDIVIDUAL', valor_inscripcion = 0, pct_pozo_competidores = 90,
                    pct_premio_rifa = 0, fecha_inicio = now(), cierre_inscripcion_en = now(),
                    reglamento_texto = 'Reglas'
                WHERE id = ?""", torneo);
        entityManager.clear();

        assertThatThrownBy(() -> designacionesService.aceptar(arbitro.getId(), torneo))
                .extracting("codigo").isEqualTo(CodigoError.TORNEO_NO_EDITABLE);
    }

    @Test
    void sinElRolDeArbitroNoAcepta() {
        jdbc.update("UPDATE usuario_rol SET eliminado_en = now() WHERE usuario_id = ?", arbitro.getId());
        entityManager.clear();

        assertThatThrownBy(() -> designacionesService.aceptar(arbitro.getId(), torneo))
                .extracting("codigo").isEqualTo(CodigoError.USUARIO_NO_ES_ARBITRO);
    }

    @Test
    void aQuienRechazoSeLoPuedeVolverADesignar() {
        designacionesService.rechazar(arbitro.getId(), torneo);

        ArbitroDetalle otraVez = arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), LOS_DOS));

        assertThat(otraVez.estado()).isEqualTo(EstadoDesignacion.PENDIENTE);
        assertThat(otraVez.permisos()).containsExactlyInAnyOrderElementsOf(LOS_DOS);
        assertThatThrownBy(() -> arbitrosService.designar(organizador, torneo,
                new DesignarArbitroRequest(arbitro.getId(), LOS_DOS)))
                .extracting("codigo").isEqualTo(CodigoError.ARBITRO_DUPLICADO);
    }

    @Test
    void siLoQuitaronAntesDeResponderNoLlegaElMail() {
        arbitrosService.quitar(organizador, torneo, arbitro.getId());

        avisoDesignacion.avisarDesignacion(new ArbitroDesignadoEnTorneo(torneo, arbitro.getId()));

        verifyNoInteractions(emailService);
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
