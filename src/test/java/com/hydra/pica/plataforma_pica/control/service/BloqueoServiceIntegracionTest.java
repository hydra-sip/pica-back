package com.hydra.pica.plataforma_pica.control.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.control.domain.AlcanceBloqueo;
import com.hydra.pica.plataforma_pica.control.domain.Bloqueo;
import com.hydra.pica.plataforma_pica.control.repository.BloqueoRepository;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

/**
 * Bloqueos con motivo y fecha de fin (HU-703) contra Postgres real: el update que levanta los
 * abiertos y los CHECK de la tabla ({@code hasta > desde}) que con mocks no se ven.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class,
        BloqueoService.class
})
class BloqueoServiceIntegracionTest {

    private final BloqueoService servicio;
    private final BloqueoRepository bloqueoRepository;
    private final PersonaRepository personaRepository;
    private final UsuarioRepository usuarioRepository;
    private final EntityManager entityManager;

    @Autowired
    BloqueoServiceIntegracionTest(
            BloqueoService servicio,
            BloqueoRepository bloqueoRepository,
            PersonaRepository personaRepository,
            UsuarioRepository usuarioRepository,
            EntityManager entityManager) {
        this.servicio = servicio;
        this.bloqueoRepository = bloqueoRepository;
        this.personaRepository = personaRepository;
        this.usuarioRepository = usuarioRepository;
        this.entityManager = entityManager;
    }

    @Test
    @DisplayName("bloquear: guarda motivo y fecha de fin, y deja al usuario BLOQUEADO")
    void bloquearGuardaLaFilaYElEstado() {
        Usuario usuario = usuario("bloqueo.alta", true);
        // en segundos justos: Postgres guarda microsegundos y redondea el resto
        Instant hasta = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);

        servicio.bloquear(usuario, "  Conducta antideportiva  ", hasta);
        usuarioRepository.saveAndFlush(usuario);
        entityManager.clear();

        Bloqueo vigente = servicio.vigente(usuario.getId()).orElseThrow();
        assertThat(vigente.getMotivo()).isEqualTo("Conducta antideportiva");
        assertThat(vigente.getHasta()).isEqualTo(hasta);
        assertThat(vigente.getAlcance()).isEqualTo(AlcanceBloqueo.USUARIO);
        assertThat(vigente.getDesde()).isNotNull();
        assertThat(usuarioRepository.findById(usuario.getId()).orElseThrow().getEstado())
                .isEqualTo(EstadoUsuario.BLOQUEADO);
    }

    @Test
    @DisplayName("levantar: cierra el bloqueo y vuelve a ACTIVO, o a PENDIENTE si el mail no está verificado")
    void levantarCierraLaFilaYRecalculaElEstado() {
        Usuario verificado = usuario("bloqueo.levantar", true);
        Usuario sinVerificar = usuario("bloqueo.levantar.pend", false);
        servicio.bloquear(verificado, "Motivo", null);
        servicio.bloquear(sinVerificar, "Motivo", null);

        servicio.levantar(verificado);
        servicio.levantar(sinVerificar);
        entityManager.flush();
        entityManager.clear();

        assertThat(servicio.vigente(verificado.getId())).isEmpty();
        assertThat(verificado.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(sinVerificar.getEstado()).isEqualTo(EstadoUsuario.PENDIENTE_VERIFICACION);
        Bloqueo levantado = bloqueoRepository.findAll().stream()
                .filter(b -> b.getUsuario().getId().equals(verificado.getId()))
                .findFirst().orElseThrow();
        assertThat(levantado.getLevantadoEn()).isNotNull();
        assertThat(levantado.getLevantadoPor()).isNotBlank();
    }

    @Test
    @DisplayName("levantarSiVencio: con la fecha de fin pasada lo levanta; vigente o indefinido, no")
    void levantarSiVencioSoloConLaFechaPasada() {
        Usuario vencido = usuario("bloqueo.vencido", true);
        Usuario vigente = usuario("bloqueo.vigente", true);
        Usuario indefinido = usuario("bloqueo.indefinido", true);
        bloqueoVencido(vencido);
        servicio.bloquear(vigente, "Motivo", Instant.now().plus(Duration.ofDays(1)));
        servicio.bloquear(indefinido, "Motivo", null);
        entityManager.flush();

        assertThat(servicio.levantarSiVencio(vencido)).isTrue();
        assertThat(servicio.levantarSiVencio(vigente)).isFalse();
        assertThat(servicio.levantarSiVencio(indefinido)).isFalse();

        assertThat(vencido.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(vigente.getEstado()).isEqualTo(EstadoUsuario.BLOQUEADO);
        assertThat(indefinido.getEstado()).isEqualTo(EstadoUsuario.BLOQUEADO);
    }

    // La API no acepta un hasta pasado (@Future): se arma desde y hasta en el pasado a mano
    private void bloqueoVencido(Usuario usuario) {
        Bloqueo bloqueo = new Bloqueo();
        bloqueo.setUsuario(usuario);
        bloqueo.setAlcance(AlcanceBloqueo.USUARIO);
        bloqueo.setMotivo("Motivo");
        bloqueo.setDesde(Instant.now().minus(Duration.ofDays(2)));
        bloqueo.setHasta(Instant.now().minus(Duration.ofDays(1)));
        bloqueoRepository.save(bloqueo);
        usuario.setEstado(EstadoUsuario.BLOQUEADO);
        usuarioRepository.saveAndFlush(usuario);
    }

    private Usuario usuario(String username, boolean emailVerificado) {
        Persona persona = new Persona();
        persona.setNombres("Ana");
        persona.setApellidos("Gómez");
        persona.setTipoDoc("DNI");
        persona.setNroDoc(String.valueOf(70000000 + Math.abs(username.hashCode() % 1000000)));
        persona.setEstado(EstadoGeneral.ACTIVO);
        personaRepository.saveAndFlush(persona);

        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(emailVerificado ? EstadoUsuario.ACTIVO : EstadoUsuario.PENDIENTE_VERIFICACION);
        usuario.setEmailVerificado(emailVerificado);
        return usuarioRepository.saveAndFlush(usuario);
    }
}
