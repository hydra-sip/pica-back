package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Búsqueda de jugadores para cargar una nómina (PICA-208) contra Postgres: por nombres, apellidos o
 * username, sin tildes, solo usuarios activos y sin datos personales. Cada test hace rollback al terminar.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class UsuarioBusquedaServiceIntegracionTest {

    @Autowired private UsuarioBusquedaService usuarioBusquedaService;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PersonaRepository personaRepository;

    @Test
    void encuentraElApellidoSinDistinguirTildes() {
        Usuario juan = usuario("jperez_busq", "Juan", "Pérez", EstadoUsuario.ACTIVO);

        assertThat(usuarioBusquedaService.buscarJugadores("perez", 0, 20).getContent())
                .containsExactly(new UsuarioPublico(juan.getId(), "jperez_busq", "Juan", "Pérez"));
    }

    @Test
    void encuentraPorNombresYPorUsername() {
        usuario("rocio_busq", "Rocío", "Gómez", EstadoUsuario.ACTIVO);
        usuario("el_tano_busq", "Martín", "Ruiz", EstadoUsuario.ACTIVO);

        assertThat(buscar("ROCIO")).containsExactly("rocio_busq");
        assertThat(buscar("tano_bus")).containsExactly("el_tano_busq");
    }

    @Test
    void conVariasPalabrasCadaUnaTieneQueAparecerEnAlgunCampo() {
        usuario("jperez_palabras", "Juan", "Pérez Palabras", EstadoUsuario.ACTIVO);
        usuario("jgomez_palabras", "Juan", "Gómez Palabras", EstadoUsuario.ACTIVO);

        assertThat(buscar("juan  perez palabras")).containsExactly("jperez_palabras");
        assertThat(buscar("palabras jgomez")).containsExactly("jgomez_palabras");
        assertThat(buscar("juan rodriguez palabras")).isEmpty();
    }

    @Test
    void elMinimoDeTresCaracteresNoCuentaLosEspacios() {
        Throwable error = catchThrowable(() -> usuarioBusquedaService.buscarJugadores("  a ", 0, 20));

        assertThat(error).isInstanceOfSatisfying(ApiException.class, api -> {
            assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
            assertThat(api.getPropiedades().get("errores"))
                    .isEqualTo(List.of(new ErrorCampo("q", ErrorCampo.Codigo.LONGITUD, "Tiene que tener al menos 3 caracteres")));
        });
    }

    @Test
    void losComodinesDeLikeSeBuscanComoTexto() {
        usuario("el_tano_comodin", "Martín", "Comodín", EstadoUsuario.ACTIVO);
        usuario("elxtano_comodin", "Marcos", "Comodín", EstadoUsuario.ACTIVO);

        assertThat(buscar("el_tano")).containsExactly("el_tano_comodin");
        assertThat(buscar("%%%")).isEmpty();
        assertThat(buscar("___")).isEmpty();
    }

    @Test
    void dejaAfueraALosBloqueadosPendientesYDadosDeBaja() {
        usuario("activo_filtro", "Ana", "Filtro", EstadoUsuario.ACTIVO);
        usuario("bloqueado_filtro", "Beto", "Filtro", EstadoUsuario.BLOQUEADO);
        usuario("pendiente_filtro", "Carla", "Filtro", EstadoUsuario.PENDIENTE_VERIFICACION);
        Usuario dadoDeBaja = usuario("baja_filtro", "Dario", "Filtro", EstadoUsuario.ACTIVO);
        dadoDeBaja.setEliminadoEn(Instant.now());
        usuarioRepository.saveAndFlush(dadoDeBaja);

        assertThat(buscar("filtro")).containsExactly("activo_filtro");
    }

    @Test
    void dejaAfueraAlUsuarioCuyaPersonaEstaDadaDeBaja() {
        Usuario usuario = usuario("sin_persona_busq", "Elena", "Personabaja", EstadoUsuario.ACTIVO);
        Persona persona = usuario.getPersona();
        persona.setEliminadoEn(Instant.now());
        personaRepository.saveAndFlush(persona);

        assertThat(buscar("personabaja")).isEmpty();
    }

    @Test
    void ordenaPorApellidosYNombresYPagina() {
        usuario("zeta_orden", "Zoe", "Orden Alvarez", EstadoUsuario.ACTIVO);
        usuario("beta_orden", "Bruno", "Orden Alvarez", EstadoUsuario.ACTIVO);
        usuario("alfa_orden", "Abril", "Orden Zarate", EstadoUsuario.ACTIVO);

        Page<UsuarioPublico> primera = usuarioBusquedaService.buscarJugadores("orden", 0, 2);

        assertThat(primera.getContent()).extracting(UsuarioPublico::username)
                .containsExactly("beta_orden", "zeta_orden");
        assertThat(primera.getTotalElements()).isEqualTo(3);
        assertThat(usuarioBusquedaService.buscarJugadores("orden", 1, 2).getContent())
                .extracting(UsuarioPublico::username)
                .containsExactly("alfa_orden");
    }

    private List<String> buscar(String q) {
        return usuarioBusquedaService.buscarJugadores(q, 0, 20).map(UsuarioPublico::username)
                .getContent();
    }

    private Usuario usuario(String username, String nombres, String apellidos, EstadoUsuario estado) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setEstado(EstadoGeneral.ACTIVO);
        persona = personaRepository.save(persona);

        Usuario usuario = new Usuario();
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(estado);
        usuario.setEmailVerificado(true);
        usuario.setPersona(persona);
        return usuarioRepository.save(usuario);
    }
}
