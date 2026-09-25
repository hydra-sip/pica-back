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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * El alta de punta a punta contra Postgres: persona + usuario + usuario_rol en una transacción,
 * con el seed de roles de V2 y los índices únicos reales. Cada test hace rollback al terminar.
 *
 * Es también la suite de integración del núcleo que pide PICA-111: username y email duplicados,
 * documento existente que vincula (no duplica) la persona, rol por defecto y persona inactiva.
 */
@SpringBootTest
@ActiveProfiles("dev")
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
        Persona persona = personaRepository.saveAndFlush(persona("40111222"));
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
    @WithMockUser(authorities = {"USUARIO_VER", "USUARIO_CREAR", "USUARIO_EDITAR", "USUARIO_ELIMINAR",
            "PERSONA_VER", "PERSONA_CREAR", "PERSONA_EDITAR", "PERSONA_ELIMINAR", "ROL_VER", "ROL_ASIGNAR"})
    void altaPorAdminNoDaRolesConPermisosQueQuienLlamaNoTiene() {
        Persona persona = personaRepository.saveAndFlush(persona("40111222"));
        Long superUsuario = rolRepository.findByNombre("SUPER_USUARIO").orElseThrow().getId();
        Long administrador = rolRepository.findByNombre("ADMINISTRADOR").orElseThrow().getId();

        // con los permisos del Administrador: SUPER_USUARIO no, ADMINISTRADOR sí
        assertThatThrownBy(() -> usuarioService.crear(NuevoUsuario.porAdmin("colado", "colado@example.com",
                "Temporal1", null, null, persona.getId(), Set.of(superUsuario))))
                .extracting("codigo").isEqualTo(CodigoError.SIN_PERMISO);
        assertThat(usuarioRepository.existsByUsernameIgnoreCase("colado")).isFalse();

        Usuario creado = usuarioService.crear(NuevoUsuario.porAdmin("agarcia", "ana@example.com", "Temporal1",
                null, null, persona.getId(), Set.of(administrador)));
        assertThat(creado.getRoles()).extracting(asignacion -> asignacion.getRol().getNombre())
                .containsExactly("ADMINISTRADOR");
    }

    @Test
    void elRegistroNoDependeDeLosPermisosDeParticipante() {
        // PARTICIPANTE se puede editar desde la pantalla de roles; el registro es anónimo y lo asigna igual
        entityManager.createNativeQuery("""
                INSERT INTO rol_permiso (rol_id, permiso_id)
                SELECT r.id, p.id FROM rol r, permiso p WHERE r.nombre = 'PARTICIPANTE' AND p.codigo = 'PERSONA_VER'
                """).executeUpdate();

        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026",
                new DatosPersona(TipoDoc.DNI, "40111222", "Juan", "Pérez", null, null, null)));

        assertThat(creado.getRoles()).extracting(asignacion -> asignacion.getRol().getNombre())
                .containsExactly("PARTICIPANTE");
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

    @Test
    void emailDuplicadoDa409SinDistinguirMayusculas() {
        usuarioService.crear(NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026",
                new DatosPersona(TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null)));

        assertThatThrownBy(() -> usuarioService.crear(NuevoUsuario.autoRegistro("otro", "Juan@Example.com",
                "Pica2026", new DatosPersona(TipoDoc.DNI, "40111222", "Ana", "García", null, null, null))))
                .extracting("codigo").isEqualTo(CodigoError.EMAIL_DUPLICADO);
    }

    @Test
    void documentoExistenteVinculaLaPersonaEnVezDeDuplicarla() {
        // la persona ya estaba (la cargó un admin, o vino de otro torneo) pero nunca tuvo usuario
        Persona existente = personaRepository.saveAndFlush(persona("30123456"));
        long personasAntes = personaRepository.count();

        Usuario creado = usuarioService.crear(NuevoUsuario.autoRegistro("jperez", "juan@example.com", "Pica2026",
                new DatosPersona(TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null)));
        entityManager.flush();
        entityManager.clear();

        assertThat(personaRepository.count()).isEqualTo(personasAntes);
        assertThat(usuarioRepository.findById(creado.getId()).orElseThrow().getPersona().getId())
                .isEqualTo(existente.getId());
    }

    @Test
    void personaInactivaOEliminadaRechazaElRegistro() {
        Persona inactiva = persona("30123456");
        inactiva.setEstado(EstadoGeneral.INACTIVO);
        personaRepository.saveAndFlush(inactiva);

        Persona eliminada = persona("40111222");
        eliminada.setEliminadoEn(Instant.now());
        personaRepository.saveAndFlush(eliminada);

        assertThatThrownBy(() -> usuarioService.crear(NuevoUsuario.autoRegistro("jperez", "juan@example.com",
                "Pica2026", new DatosPersona(TipoDoc.DNI, "30123456", "Juan", "Pérez", null, null, null))))
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_INACTIVA);
        assertThatThrownBy(() -> usuarioService.crear(NuevoUsuario.autoRegistro("agarcia", "ana@example.com",
                "Pica2026", new DatosPersona(TipoDoc.DNI, "40111222", "Ana", "García", null, null, null))))
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_INACTIVA);
        assertThat(usuarioRepository.count()).isZero();
    }

    private static Persona persona(String nroDoc) {
        Persona persona = new Persona();
        persona.setNombres("Juan");
        persona.setApellidos("Pérez");
        persona.setTipoDoc("DNI");
        persona.setNroDoc(nroDoc);
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }
}
