package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * El Admin del sistema que {@link AdminInicial} crea al levantar el contexto. Es el único test con
 * initial-password: al resto no le conviene un usuario "admin" que no armó él.
 */
@SpringBootTest(properties = "app.admin.initial-password=test-initial-password")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class AdminInicialIntegracionTest {

    @Autowired private AdminInicial adminInicial;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private UsuarioRolRepository usuarioRolRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;

    @Test
    void alArrancarQuedaElAdminListoParaEntrar() {
        Usuario admin = usuarioRepository.findByUsernameIgnoreCase("admin").orElseThrow();

        assertThat(admin.getEmail()).isEqualTo("admin@pica.local");
        assertThat(admin.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(admin.isEmailVerificado()).isTrue();
        assertThat(passwordEncoder.matches("test-initial-password", admin.getPasswordHash())).isTrue();
        assertThat(admin.getPersona().getNombres()).isEqualTo("Admin");
        assertThat(admin.getPersona().getNroDoc()).isNull();
        assertThat(usuarioRolRepository.findByUsuarioId(admin.getId()))
                .extracting(asignacion -> asignacion.getRol().getNombre())
                .containsExactly("SUPER_USUARIO");
    }

    @Test
    void siYaExisteNoLoDuplicaNiLeVuelveLaContrasenaInicial() throws Exception {
        Usuario admin = usuarioRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        admin.setPasswordHash(passwordEncoder.encode("OtraClave2026"));
        usuarioRepository.saveAndFlush(admin);
        long usuarios = usuarioRepository.count();

        adminInicial.run(new DefaultApplicationArguments());
        entityManager.flush();
        entityManager.clear();

        assertThat(usuarioRepository.count()).isEqualTo(usuarios);
        Usuario leido = usuarioRepository.findById(admin.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("OtraClave2026", leido.getPasswordHash())).isTrue();
    }
}
