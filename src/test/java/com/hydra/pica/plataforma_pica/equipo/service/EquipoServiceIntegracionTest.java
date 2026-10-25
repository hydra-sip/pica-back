package com.hydra.pica.plataforma_pica.equipo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.equipo.dto.EquipoResumen;
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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mis equipos (PICA-234) contra Postgres: los equipos de los que el usuario es capitán, sin los dados de baja,
 * ordenados por nombre. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class EquipoServiceIntegracionTest {

    @Autowired private EquipoService equipoService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long capitan;

    @BeforeEach
    void escenario() {
        capitan = usuario("cap_mios").getId();
    }

    @Test
    void listaLosEquiposDelCapitanOrdenadosPorNombre() {
        long zorros = equipo("Zorros", capitan);
        long aguilas = equipo("Aguilas", capitan);
        long halcones = equipo("Halcones", capitan);

        Page<EquipoResumen> pagina = equipoService.listarMios(capitan, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).extracting(EquipoResumen::id, EquipoResumen::nombre)
                .containsExactly(tuple(aguilas, "Aguilas"), tuple(halcones, "Halcones"), tuple(zorros, "Zorros"));
    }

    @Test
    void noMuestraLosEquiposDeOtroCapitanNiDondeSoloEsIntegrante() {
        long propio = equipo("Propio", capitan);
        Long otroCapitan = usuario("otro_cap_mios").getId();
        equipo("Ajeno", otroCapitan);
        long dondeSoloIntegro = equipo("Integrante", otroCapitan);
        integrante(dondeSoloIntegro, capitan);

        Page<EquipoResumen> pagina = equipoService.listarMios(capitan, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).extracting(EquipoResumen::id).containsExactly(propio);
    }

    @Test
    void noMuestraLosEquiposDadosDeBaja() {
        long vigente = equipo("Vigente", capitan);
        long dadoDeBaja = equipo("Disuelto", capitan);
        jdbc.update("UPDATE equipo SET eliminado_en = now() WHERE id = ?", dadoDeBaja);

        Page<EquipoResumen> pagina = equipoService.listarMios(capitan, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).extracting(EquipoResumen::id).containsExactly(vigente);
    }

    @Test
    void paginaLosEquipos() {
        long a = equipo("A", capitan);
        long b = equipo("B", capitan);
        long c = equipo("C", capitan);

        Page<EquipoResumen> segunda = equipoService.listarMios(capitan, PageRequest.of(1, 2));

        assertThat(segunda.getContent()).extracting(EquipoResumen::id).containsExactly(c);
        assertThat(segunda.getTotalElements()).isEqualTo(3);
        assertThat(segunda.getTotalPages()).isEqualTo(2);
        assertThat(equipoService.listarMios(capitan, PageRequest.of(0, 2)).getContent())
                .extracting(EquipoResumen::id).containsExactly(a, b);
    }

    @Test
    void sinEquiposDevuelveUnaPaginaVacia() {
        Page<EquipoResumen> pagina = equipoService.listarMios(capitan, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).isEmpty();
        assertThat(pagina.getTotalElements()).isZero();
    }

    @Test
    void elOrdenDelClienteSeIgnora() {
        long a = equipo("A", capitan);
        long b = equipo("B", capitan);

        Page<EquipoResumen> pagina = equipoService.listarMios(capitan,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")));

        assertThat(pagina.getContent()).extracting(EquipoResumen::id).containsExactly(a, b);
    }

    // --- helpers ------------------------------------------------------------

    private long equipo(String nombre, Long capitanId) {
        long id = jdbc.queryForObject("INSERT INTO equipo (capitan_id, nombre) VALUES (?, ?) RETURNING id", Long.class, capitanId, nombre);
        integrante(id, capitanId);
        return id;
    }

    private void integrante(long equipoId, Long usuarioId) {
        jdbc.update("INSERT INTO usuario_equipo (usuario_id, equipo_id) VALUES (?, ?)", usuarioId, equipoId);
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
