package com.hydra.pica.plataforma_pica.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.config.JpaAuditingConfig;
import com.hydra.pica.plataforma_pica.common.security.SecurityContextCurrentUserProvider;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.PersonaAdminRepositoryCustom.PersonaAdminRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * El listado del ABM de personas es SQL nativo: se prueba contra Postgres real (filtros, eliminadas,
 * tieneUsuario, orden y conteo), porque con mocks no se vería un error de SQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        SecurityContextCurrentUserProvider.class,
        TestcontainersConfiguration.class
})
class PersonaAdminRepositoryTest {

    @Autowired
    private PersonaRepository personaRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    private Persona perez;
    private Persona gomez;
    private Persona eliminada;

    @BeforeEach
    void cargarDatos() {
        perez = guardar("Juan", "Pérez", "30111111", EstadoGeneral.ACTIVO, null);
        gomez = guardar("Ana", "Gómez", "30222222", EstadoGeneral.INACTIVO, null);
        eliminada = guardar("Luis", "Pérez Ruiz", "30333333", EstadoGeneral.ACTIVO, Instant.now());
        crearUsuario("jperez", perez, null);
        crearUsuario("lruiz", eliminada, Instant.now());
    }

    private static final PageRequest PAGINA = PageRequest.of(0, 20, Sort.by("apellidos", "nombres"));

    @Test
    @DisplayName("Sin filtros trae solo las vivas, con tieneUsuario, ordenadas por apellidos")
    void sinFiltros() {
        Page<PersonaAdminRow> pagina = personaRepository.buscar(null, null, false, PAGINA);

        assertThat(pagina.getContent()).extracting(PersonaAdminRow::id).containsExactly(gomez.getId(), perez.getId());
        assertThat(pagina.getTotalElements()).isEqualTo(2);
        assertThat(pagina.getContent()).extracting(PersonaAdminRow::tieneUsuario).containsExactly(false, true);
        assertThat(pagina.getContent()).allSatisfy(fila -> assertThat(fila.eliminadoEn()).isNull());
    }

    @Test
    @DisplayName("incluirEliminados trae también las dadas de baja, con la fecha; su usuario eliminado cuenta como tieneUsuario")
    void incluyeEliminadas() {
        Page<PersonaAdminRow> pagina = personaRepository.buscar(null, null, true, PAGINA);

        assertThat(pagina.getContent()).extracting(PersonaAdminRow::id)
                .containsExactly(gomez.getId(), perez.getId(), eliminada.getId());
        PersonaAdminRow fila = pagina.getContent().get(2);
        assertThat(fila.eliminadoEn()).isNotNull();
        assertThat(fila.tieneUsuario()).isTrue();
    }

    @Test
    @DisplayName("q busca por apellido (sin distinguir mayúsculas, parcial) o por número de documento")
    void filtraPorTexto() {
        assertThat(personaRepository.buscar("PÉREZ", null, false, PAGINA).getContent())
                .extracting(PersonaAdminRow::id).containsExactly(perez.getId());
        assertThat(personaRepository.buscar("2222", null, false, PAGINA).getContent())
                .extracting(PersonaAdminRow::id).containsExactly(gomez.getId());
        assertThat(personaRepository.buscar("  ", null, false, PAGINA).getTotalElements()).isEqualTo(2);
        assertThat(personaRepository.buscar("no-existe", null, false, PAGINA).getContent()).isEmpty();
    }

    @Test
    @DisplayName("estado filtra por ACTIVO o INACTIVO")
    void filtraPorEstado() {
        assertThat(personaRepository.buscar(null, EstadoGeneral.INACTIVO, false, PAGINA).getContent())
                .extracting(PersonaAdminRow::id).containsExactly(gomez.getId());
        assertThat(personaRepository.buscar(null, EstadoGeneral.ACTIVO, true, PAGINA).getContent())
                .extracting(PersonaAdminRow::id).containsExactly(perez.getId(), eliminada.getId());
    }

    @Test
    @DisplayName("Ordena por los campos permitidos, ignora los que no y pagina con el total correcto")
    void ordenYPaginacion() {
        Page<PersonaAdminRow> desc = personaRepository.buscar(null, null, true,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "nroDoc")));
        assertThat(desc.getContent()).extracting(PersonaAdminRow::nroDoc).containsExactly("30333333", "30222222");
        assertThat(desc.getTotalElements()).isEqualTo(3);
        assertThat(desc.getTotalPages()).isEqualTo(2);

        Page<PersonaAdminRow> segunda = personaRepository.buscar(null, null, true,
                PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "nroDoc")));
        assertThat(segunda.getContent()).extracting(PersonaAdminRow::nroDoc).containsExactly("30111111");

        // un campo que no está en la lista blanca no llega al SQL
        assertThat(personaRepository.buscar(null, null, false,
                PageRequest.of(0, 20, Sort.by("passwordHash; drop table persona"))).getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("Las consultas de la ficha ven a la persona eliminada y a su usuario eliminado")
    void consultasDeLaFicha() {
        assertThat(personaRepository.findById(eliminada.getId())).isEmpty();
        assertThat(personaRepository.findByIdIncluyendoEliminadas(eliminada.getId())).isPresent();
        assertThat(usuarioRepository.findByPersonaIdIncluyendoEliminados(eliminada.getId()))
                .get().extracting(Usuario::getUsername).isEqualTo("lruiz");
        assertThat(usuarioRepository.findByPersonaIdIncluyendoEliminados(gomez.getId())).isEmpty();
    }

    private Persona guardar(String nombres, String apellidos, String nroDoc, EstadoGeneral estado,
                            Instant eliminadoEn) {
        Persona persona = new Persona();
        persona.setNombres(nombres);
        persona.setApellidos(apellidos);
        persona.setTipoDoc("DNI");
        persona.setNroDoc(nroDoc);
        persona.setEstado(estado);
        persona.setEliminadoEn(eliminadoEn);
        return personaRepository.saveAndFlush(persona);
    }

    private void crearUsuario(String username, Persona persona, Instant eliminadoEn) {
        Usuario usuario = new Usuario();
        usuario.setPersona(persona);
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEmailVerificado(true);
        usuario.setEliminadoEn(eliminadoEn);
        usuarioRepository.saveAndFlush(usuario);
    }
}
