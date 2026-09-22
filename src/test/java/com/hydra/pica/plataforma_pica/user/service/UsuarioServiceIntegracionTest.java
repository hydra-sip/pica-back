package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import jakarta.persistence.EntityManager;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.event.UsuarioCreado;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.RolRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El alta de punta a punta contra Postgres: persona + usuario + usuario_rol en una transacción,
 * con el seed de roles de V2 y los índices únicos reales. Cada test hace rollback al terminar.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
@Transactional
class UsuarioServiceIntegracionTest {

    @Autowired private UsuarioService usuarioService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private RolRepository rolRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents eventos;

    @Test
    void autoRegistroCreaPersonaUsuarioYRolParticipante() {
        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026",
                new DatosPersona(TipoDoc.DNI, "30123456", "Juan", "Pérez", LocalDate.of(1990, 5, 17), null, null)));
        entityManager.flush();
        entityManager.clear();

        Usuario leido = usuarioRepository.findById(creado.getId()).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        assertThat(leido.isEmailVerificado()).isFalse();
        assertThat(passwordEncoder.matches("Pica2026", leido.getPasswordHash())).isTrue();
        assertThat(leido.getPasswordHash()).startsWith("$2a$12$");
        assertThat(leido.getPersona().getNroDoc()).isEqualTo("30123456");
        assertThat(leido.getPersona().getCreadoEn()).isNotNull();

        assertThat(usuarioRolRepository.findByUsuarioId(creado.getId()))
                .singleElement()
                .extracting(ur -> ur.getRol().getNombre()).isEqualTo("PARTICIPANTE");

        assertThat(eventos.stream(UsuarioCreado.class))
                .containsExactly(new UsuarioCreado(creado.getId(), "jperez", "juan@example.com", true));
    }

    @Test
    void segundoRegistroConMismoDocumentoDa409PersonaConUsuario() {
        DatosPersona juan = new DatosPersona(TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null);
        usuarioService.crear(NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026", juan));

        assertThatThrownBy(() -> usuarioService.crear(
                NuevoUsuario.autoRegistro("jperez2", "otro@example.com", "Pica2026", juan)))
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_CON_USUARIO);
    }

    @Test
    void usernameDeUnUsuarioEliminadoNoSePuedeReutilizar() {
        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026",
                new DatosPersona(TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null)));
        creado.setEliminadoEn(Instant.now());
        usuarioRepository.saveAndFlush(creado);
        entityManager.clear();

        assertThat(usuarioRepository.existsByUsernameIgnoreCase("JPEREZ")).isFalse();
        assertThatThrownBy(() -> usuarioService.crear(NuevoUsuario.autoRegistro("JPEREZ", "nuevo@example.com",
                "Pica2026", new DatosPersona(TipoDoc.DNI, "40111222", "Ana", "García", null, null, null))))
                .extracting("codigo").isEqualTo(CodigoError.USERNAME_DUPLICADO);
    }

    @Test
    void altaPorAdminConPersonaExistenteYRolesPorId() {
        Persona persona = new Persona();
        persona.setNombres("Ana");
        persona.setApellidos("García");
        persona.setTipoDoc("DNI");
        persona.setNroDoc("40111222");
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.saveAndFlush(persona);
        Long organizador = rolRepository.findByNombre("ORGANIZADOR").orElseThrow().getId();
        Long arbitro = rolRepository.findByNombre("ARBITRO").orElseThrow().getId();

        Usuario creado = usuarioService.crear(NuevoUsuario.porAdmin("agarcia", "ana@example.com", "Temporal1",
                null, null, persona.getId(), Set.of(organizador, arbitro)));
        entityManager.flush();
        entityManager.clear();

        Usuario leido = usuarioRepository.findById(creado.getId()).orElseThrow();
        assertThat(leido.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(leido.isEmailVerificado()).isTrue();
        assertThat(usuarioRolRepository.findByUsuarioId(creado.getId()))
                .extracting(UsuarioRol::getRol)
                .extracting(r -> r.getNombre())
                .containsExactlyInAnyOrder("ORGANIZADOR", "ARBITRO");
        assertThat(eventos.stream(UsuarioCreado.class))
                .singleElement().extracting(UsuarioCreado::requiereVerificacion).isEqualTo(false);
    }

    @Test
    void googleCreaSinDocumentoNiContrasena() {
        Usuario creado = usuarioService.crear(
                NuevoUsuario.desdeGoogle("juan.perez@gmail.com", "google-sub-1", "Juan", "Pérez"));
        entityManager.flush();
        entityManager.clear();

        Usuario leido = usuarioRepository.findByGoogleSub("google-sub-1").orElseThrow();
        assertThat(leido.getId()).isEqualTo(creado.getId());
        assertThat(leido.getUsername()).isEqualTo("juan.perez");
        assertThat(leido.getPasswordHash()).isNull();
        assertThat(leido.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(leido.getPersona().getTipoDoc()).isNull();
        assertThat(leido.getPersona().getNroDoc()).isNull();
    }
}
