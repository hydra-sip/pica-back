package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.PersonaDetalle;
import com.hydra.pica.plataforma_pica.user.dto.PersonaRequest;
import com.hydra.pica.plataforma_pica.user.dto.PersonaResumen;
import com.hydra.pica.plataforma_pica.user.repository.PersonaAdminRepositoryCustom.PersonaAdminRow;
import com.hydra.pica.plataforma_pica.user.repository.PersonaRepository;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** ABM de personas (PICA-123) con los repositorios mockeados. */
@ExtendWith(MockitoExtension.class)
class PersonaAdminServiceTest {

    private static final Long ID = 10L;

    @Mock
    private PersonaRepository personaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private PersonaAdminService servicio;

    // --- listar ------------------------------------------------------------------

    @Test
    @DisplayName("listar: arma el resumen con eliminado y tieneUsuario a partir de la fila")
    void listarArmaElResumen() {
        PageRequest pagina = PageRequest.of(0, 20);
        PersonaAdminRow viva = new PersonaAdminRow(1L, "Juan", "Pérez", "DNI", "30123456",
                EstadoGeneral.ACTIVO, null, true);
        PersonaAdminRow eliminada = new PersonaAdminRow(2L, "Ana", "Gómez", "DNI", "20111222",
                EstadoGeneral.INACTIVO, Instant.parse("2026-01-01T00:00:00Z"), false);
        when(personaRepository.buscar("perez", null, true, pagina))
                .thenReturn(new PageImpl<>(List.of(viva, eliminada), pagina, 2));

        Page<PersonaResumen> resultado = servicio.listar("perez", null, true, pagina);

        assertThat(resultado.getContent()).containsExactly(
                new PersonaResumen(1L, "Juan", "Pérez", "DNI", "30123456", EstadoGeneral.ACTIVO, false, true),
                new PersonaResumen(2L, "Ana", "Gómez", "DNI", "20111222", EstadoGeneral.INACTIVO, true, false));
        assertThat(resultado.getTotalElements()).isEqualTo(2);
    }

    // --- obtener -----------------------------------------------------------------

    @Test
    @DisplayName("obtener: trae también a una persona eliminada, con su usuario vinculado")
    void obtenerIncluyeEliminadaYUsuario() {
        Persona persona = persona(ID, "30123456");
        persona.setEliminadoEn(Instant.parse("2026-01-01T00:00:00Z"));
        Usuario usuario = usuario(5L, "juan", Instant.parse("2026-01-01T00:00:00Z"));
        when(personaRepository.findByIdIncluyendoEliminadas(ID)).thenReturn(Optional.of(persona));
        when(usuarioRepository.findByPersonaIdIncluyendoEliminados(ID)).thenReturn(Optional.of(usuario));

        PersonaDetalle detalle = servicio.obtener(ID);

        assertThat(detalle.eliminado()).isTrue();
        assertThat(detalle.usuario().username()).isEqualTo("juan");
        assertThat(detalle.usuario().eliminado()).isTrue();
    }

    @Test
    @DisplayName("obtener: una persona sin usuario devuelve usuario null; una inexistente, 404")
    void obtenerSinUsuarioOInexistente() {
        when(personaRepository.findByIdIncluyendoEliminadas(ID)).thenReturn(Optional.of(persona(ID, "30123456")));
        when(usuarioRepository.findByPersonaIdIncluyendoEliminados(ID)).thenReturn(Optional.empty());
        when(personaRepository.findByIdIncluyendoEliminadas(99L)).thenReturn(Optional.empty());

        assertThat(servicio.obtener(ID).usuario()).isNull();
        assertThatThrownBy(() -> servicio.obtener(99L))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_NO_ENCONTRADA);
    }

    // --- crear -------------------------------------------------------------------

    @Test
    @DisplayName("crear: guarda todos los campos, documento en mayúsculas, ACTIVO si no viene estado y un opcional vacío en null")
    void creaLaPersona() {
        when(personaRepository.findByDocumentoIncluyendoEliminadas("PASAPORTE", "AAB123456"))
                .thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(any(Persona.class))).thenAnswer(inv -> inv.getArgument(0));

        PersonaDetalle detalle = servicio.crear(new PersonaRequest(" Juan ", "Pérez", TipoDoc.PASAPORTE, "aab123456",
                LocalDate.of(1990, 5, 20), "  ", "1155551234", null, null));

        assertThat(detalle.nombres()).isEqualTo("Juan");
        assertThat(detalle.tipoDoc()).isEqualTo("PASAPORTE");
        assertThat(detalle.nroDoc()).isEqualTo("AAB123456");
        assertThat(detalle.fechaNacimiento()).isEqualTo(LocalDate.of(1990, 5, 20));
        assertThat(detalle.domicilioPostal()).isNull();
        assertThat(detalle.telefono()).isEqualTo("1155551234");
        assertThat(detalle.estado()).isEqualTo(EstadoGeneral.ACTIVO);
        assertThat(detalle.usuario()).isNull();
    }

    @Test
    @DisplayName("crear: respeta el estado que se manda")
    void creaConEstado() {
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456")).thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(any(Persona.class))).thenAnswer(inv -> inv.getArgument(0));

        PersonaDetalle detalle = servicio.crear(pedido("30123456", EstadoGeneral.INACTIVO));

        assertThat(detalle.estado()).isEqualTo(EstadoGeneral.INACTIVO);
    }

    @Test
    @DisplayName("crear: un documento de otra persona, aunque esté eliminada, da 409 DOCUMENTO_DUPLICADO")
    void crearConDocumentoRepetido() {
        Persona eliminada = persona(99L, "30123456");
        eliminada.setEliminadoEn(Instant.now());
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.of(eliminada));

        assertThatThrownBy(() -> servicio.crear(pedido("30123456", null)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.DOCUMENTO_DUPLICADO);
        verify(personaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("crear: dos altas a la vez con el mismo documento: el choque con el índice único es 409")
    void crearChoqueConElIndiceUnico() {
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456")).thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(any(Persona.class)))
                .thenThrow(new DataIntegrityViolationException("dup",
                        new ConstraintViolationException("dup", new SQLException(), "uq_persona_tipo_doc_nro_doc")));

        assertThatThrownBy(() -> servicio.crear(pedido("30123456", null)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.DOCUMENTO_DUPLICADO);
    }

    @Test
    @DisplayName("crear: otra violación de integridad se propaga tal cual")
    void crearOtraViolacionSePropaga() {
        DataIntegrityViolationException otra = new DataIntegrityViolationException("nombres not null");
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456")).thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(any(Persona.class))).thenThrow(otra);

        assertThatThrownBy(() -> servicio.crear(pedido("30123456", null))).isSameAs(otra);
    }

    // --- modificar ---------------------------------------------------------------

    @Test
    @DisplayName("modificar: reemplaza los campos; sin estado en el request conserva el que tenía")
    void modificaConservandoElEstado() {
        Persona persona = persona(ID, "30123456");
        persona.setEstado(EstadoGeneral.INACTIVO);
        persona.setTelefono("viejo");
        when(personaRepository.findById(ID)).thenReturn(Optional.of(persona));
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "40111222")).thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(persona)).thenReturn(persona);

        PersonaDetalle detalle = servicio.modificar(ID, new PersonaRequest("Juan Carlos", "Pérez", TipoDoc.DNI,
                "40111222", null, null, null, null, null));

        assertThat(persona.getNombres()).isEqualTo("Juan Carlos");
        assertThat(persona.getNroDoc()).isEqualTo("40111222");
        assertThat(persona.getTelefono()).isNull();
        assertThat(detalle.estado()).isEqualTo(EstadoGeneral.INACTIVO);
    }

    @Test
    @DisplayName("modificar: con estado en el request lo cambia")
    void modificaElEstado() {
        Persona persona = persona(ID, "30123456");
        when(personaRepository.findById(ID)).thenReturn(Optional.of(persona));
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.of(persona));
        when(personaRepository.saveAndFlush(persona)).thenReturn(persona);

        servicio.modificar(ID, pedido("30123456", EstadoGeneral.INACTIVO));

        assertThat(persona.getEstado()).isEqualTo(EstadoGeneral.INACTIVO);
    }

    @Test
    @DisplayName("modificar: reenviar el propio documento no es duplicado; el de otra persona sí (409)")
    void modificarDocumentoPropioYAjeno() {
        Persona persona = persona(ID, "30123456");
        when(personaRepository.findById(ID)).thenReturn(Optional.of(persona));
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "30123456"))
                .thenReturn(Optional.of(persona));
        when(personaRepository.findByDocumentoIncluyendoEliminadas("DNI", "40111222"))
                .thenReturn(Optional.of(persona(99L, "40111222")));
        when(personaRepository.saveAndFlush(persona)).thenReturn(persona);

        servicio.modificar(ID, pedido("30123456", null));
        assertThatThrownBy(() -> servicio.modificar(ID, pedido("40111222", null)))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.DOCUMENTO_DUPLICADO);
        assertThat(persona.getNroDoc()).isEqualTo("30123456");
    }

    @Test
    @DisplayName("modificar: una persona inexistente o dada de baja es 404")
    void modificarInexistente() {
        when(personaRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.modificar(99L, pedido("30123456", null)))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_NO_ENCONTRADA);
    }

    // --- eliminar ----------------------------------------------------------------

    @Test
    @DisplayName("eliminar: sin usuario, o con un usuario ya dado de baja, marca la baja")
    void eliminaSinUsuarioVivo() {
        Persona sinUsuario = persona(ID, "30123456");
        Persona conUsuarioEliminado = persona(11L, "40111222");
        when(personaRepository.findByIdIncluyendoEliminadas(ID)).thenReturn(Optional.of(sinUsuario));
        when(personaRepository.findByIdIncluyendoEliminadas(11L)).thenReturn(Optional.of(conUsuarioEliminado));
        when(usuarioRepository.findByPersonaIdIncluyendoEliminados(ID)).thenReturn(Optional.empty());
        when(usuarioRepository.findByPersonaIdIncluyendoEliminados(11L))
                .thenReturn(Optional.of(usuario(6L, "ana", Instant.now())));

        servicio.eliminar(ID);
        servicio.eliminar(11L);

        assertThat(sinUsuario.getEliminadoEn()).isNotNull();
        assertThat(conUsuarioEliminado.getEliminadoEn()).isNotNull();
        verify(personaRepository).saveAndFlush(sinUsuario);
        verify(personaRepository).saveAndFlush(conUsuarioEliminado);
    }

    @Test
    @DisplayName("eliminar: con un usuario activo (o bloqueado, sin baja) da 409 PERSONA_CON_USUARIO y no toca nada")
    void eliminarConUsuarioVivo() {
        Persona persona = persona(ID, "30123456");
        when(personaRepository.findByIdIncluyendoEliminadas(ID)).thenReturn(Optional.of(persona));
        when(usuarioRepository.findByPersonaIdIncluyendoEliminados(ID))
                .thenReturn(Optional.of(usuario(5L, "juan", null)));

        assertThatThrownBy(() -> servicio.eliminar(ID))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_CON_USUARIO);
        assertThat(persona.getEliminadoEn()).isNull();
        verify(personaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("eliminar: una persona inexistente es 404")
    void eliminarInexistente() {
        when(personaRepository.findByIdIncluyendoEliminadas(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.eliminar(99L))
                .isInstanceOf(NoEncontradoException.class)
                .extracting("codigo").isEqualTo(CodigoError.PERSONA_NO_ENCONTRADA);
    }

    @Test
    @DisplayName("eliminar: es idempotente, una persona ya dada de baja no se toca y no se consulta su usuario")
    void eliminarUnaYaDadaDeBaja() {
        Persona persona = persona(ID, "30123456");
        Instant baja = Instant.parse("2026-01-01T00:00:00Z");
        persona.setEliminadoEn(baja);
        when(personaRepository.findByIdIncluyendoEliminadas(ID)).thenReturn(Optional.of(persona));

        servicio.eliminar(ID);

        assertThat(persona.getEliminadoEn()).isEqualTo(baja);
        verify(personaRepository, never()).saveAndFlush(any());
        verify(usuarioRepository, never()).findByPersonaIdIncluyendoEliminados(any());
    }

    // --- reactivar ---------------------------------------------------------------

    @Test
    @DisplayName("reactivar: limpia la baja y devuelve la persona; si no existe, 404")
    void reactiva() {
        Persona persona = persona(ID, "30123456");
        persona.setEliminadoEn(Instant.parse("2026-01-01T00:00:00Z"));
        when(personaRepository.findByIdIncluyendoEliminadas(ID)).thenReturn(Optional.of(persona));
        when(personaRepository.findByIdIncluyendoEliminadas(99L)).thenReturn(Optional.empty());
        when(personaRepository.saveAndFlush(persona)).thenReturn(persona);
        when(usuarioRepository.findByPersonaIdIncluyendoEliminados(ID)).thenReturn(Optional.empty());

        PersonaDetalle detalle = servicio.reactivar(ID);

        assertThat(persona.getEliminadoEn()).isNull();
        assertThat(detalle.eliminado()).isFalse();
        assertThatThrownBy(() -> servicio.reactivar(99L)).isInstanceOf(NoEncontradoException.class);
    }

    // --- helpers -----------------------------------------------------------------

    private static PersonaRequest pedido(String nroDoc, EstadoGeneral estado) {
        return new PersonaRequest("Juan", "Pérez", TipoDoc.DNI, nroDoc, LocalDate.of(1990, 5, 20),
                "Calle 123", "1155551234", null, estado);
    }

    private static Persona persona(Long id, String nroDoc) {
        Persona persona = new Persona();
        ReflectionTestUtils.setField(persona, "id", id);
        persona.setNombres("Juan");
        persona.setApellidos("Pérez");
        persona.setTipoDoc("DNI");
        persona.setNroDoc(nroDoc);
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }

    private static Usuario usuario(Long id, String username, Instant eliminadoEn) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", id);
        usuario.setUsername(username);
        usuario.setEmail(username + "@example.com");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setEliminadoEn(eliminadoEn);
        return usuario;
    }
}
