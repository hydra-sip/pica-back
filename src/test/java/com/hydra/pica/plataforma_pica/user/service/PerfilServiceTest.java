package com.hydra.pica.plataforma_pica.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import com.hydra.pica.plataforma_pica.user.dto.Me;
import com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest;
import com.hydra.pica.plataforma_pica.user.dto.RolMinimo;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * GET y PUT /me (PICA-121) con los repositorios mockeados. Que el documento no choque contra otra
 * persona lo prueba {@code PersonaServiceTest}; acá solo importa cuándo se lo llama.
 */
@ExtendWith(MockitoExtension.class)
class PerfilServiceTest {

    private static final Long ID = 5L;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PermisoService permisoService;

    @Mock
    private PersonaService personaService;

    @InjectMocks
    private PerfilService perfilService;

    @Test
    @DisplayName("obtener: arma el Me con roles por nombre y permisos ordenados")
    void obtenerArmaElMe() {
        Usuario usuario = usuario(personaCompleta());
        usuario.getRoles().add(new UsuarioRol(usuario, rol(3L, "PARTICIPANTE")));
        usuario.getRoles().add(new UsuarioRol(usuario, rol(1L, "ADMIN")));
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(permisoService.permisosDe(ID)).thenReturn(Set.of("ROL_VER", "PERSONA_VER", "USUARIO_VER"));

        Me me = perfilService.obtener(ID);

        assertThat(me.usuario()).isEqualTo(
                new Me.DatosUsuario(ID, "jperez", "jperez@example.com", EstadoUsuario.ACTIVO, true));
        assertThat(me.persona().nombres()).isEqualTo("Juan");
        assertThat(me.roles()).extracting(RolMinimo::nombre).containsExactly("ADMIN", "PARTICIPANTE");
        assertThat(me.permisos()).containsExactly("PERSONA_VER", "ROL_VER", "USUARIO_VER");
        assertThat(me.datosCompletos()).isTrue();
    }

    @Test
    @DisplayName("obtener: un usuario de Google sin contraseña ni datos tiene datosCompletos en false")
    void usuarioDeGoogle() {
        Usuario usuario = usuario(personaSinDocumento());
        usuario.setPasswordHash(null);
        usuario.setGoogleSub("google-123");
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario));
        when(permisoService.permisosDe(ID)).thenReturn(Set.of());

        Me me = perfilService.obtener(ID);

        assertThat(me.usuario().tieneContrasena()).isFalse();
        assertThat(me.datosCompletos()).isFalse();
    }

    @Test
    @DisplayName("obtener: con documento pero sin teléfono, datosCompletos es false")
    void faltaUnDato() {
        Persona persona = personaCompleta();
        persona.setTelefono(null);
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario(persona)));
        when(permisoService.permisosDe(ID)).thenReturn(Set.of());

        assertThat(perfilService.obtener(ID).datosCompletos()).isFalse();
    }

    @Test
    @DisplayName("obtener: si el usuario de la sesión ya no existe es 401 NO_AUTENTICADO, no 404")
    void usuarioInexistente() {
        when(usuarioRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> perfilService.obtener(ID))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(((ApiException) e).getCodigo()).isEqualTo(CodigoError.NO_AUTENTICADO);
                });
    }

    @Test
    @DisplayName("actualizar: reemplaza los datos, recorta espacios y un opcional vacío o null se borra")
    void actualizaDatos() {
        Persona persona = personaCompleta();
        persona.setDescripcion("La carga el admin");
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario(persona)));
        when(permisoService.permisosDe(ID)).thenReturn(Set.of());

        Me me = perfilService.actualizar(ID, new MeUpdateRequest(
                "  Juan Carlos ", " Pérez", LocalDate.of(1991, 1, 2), "   ", null, null, null));

        assertThat(persona.getNombres()).isEqualTo("Juan Carlos");
        assertThat(persona.getApellidos()).isEqualTo("Pérez");
        assertThat(persona.getFechaNacimiento()).isEqualTo(LocalDate.of(1991, 1, 2));
        assertThat(persona.getDomicilioPostal()).isNull();
        assertThat(persona.getTelefono()).isNull();
        // lo que no está en el request no se toca
        assertThat(persona.getDescripcion()).isEqualTo("La carga el admin");
        assertThat(persona.getTipoDoc()).isEqualTo("DNI");
        assertThat(persona.getNroDoc()).isEqualTo("30123456");
        assertThat(me.datosCompletos()).isFalse();
        verify(personaService, never()).asignarDocumento(any(), any(), any());
    }

    @Test
    @DisplayName("actualizar: si la persona no tenía documento, lo carga con PersonaService")
    void cargaDocumentoNuevo() {
        Persona persona = personaSinDocumento();
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario(persona)));
        when(permisoService.permisosDe(ID)).thenReturn(Set.of());

        perfilService.actualizar(ID, pedido(TipoDoc.DNI, " 40111222 "));

        verify(personaService).asignarDocumento(persona, TipoDoc.DNI, "40111222");
    }

    @Test
    @DisplayName("actualizar: reenviar el mismo documento que ya tiene está permitido")
    void mismoDocumento() {
        Persona persona = personaCompleta();
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario(persona)));
        when(permisoService.permisosDe(ID)).thenReturn(Set.of());

        perfilService.actualizar(ID, pedido(TipoDoc.DNI, "30123456"));

        assertThat(persona.getNroDoc()).isEqualTo("30123456");
        verify(personaService, never()).asignarDocumento(any(), any(), any());
    }

    @Test
    @DisplayName("actualizar: cambiar un documento ya cargado da 409 DOCUMENTO_NO_EDITABLE")
    void documentoNoEditable() {
        Persona persona = personaCompleta();
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario(persona)));

        assertThatThrownBy(() -> perfilService.actualizar(ID, pedido(TipoDoc.PASAPORTE, "30123456")))
                .isInstanceOf(ConflictoException.class)
                .extracting("codigo").isEqualTo(CodigoError.DOCUMENTO_NO_EDITABLE);
        verify(personaService, never()).asignarDocumento(any(), any(), any());
    }

    @Test
    @DisplayName("actualizar: tipoDoc sin nroDoc da 400 VALIDACION marcando nroDoc")
    void documentoAMedias() {
        when(usuarioRepository.findById(ID)).thenReturn(Optional.of(usuario(personaSinDocumento())));

        assertThatThrownBy(() -> perfilService.actualizar(ID, pedido(TipoDoc.DNI, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCodigo()).isEqualTo(CodigoError.VALIDACION);
                    assertThat(api.getPropiedades().get("errores").toString()).contains("nroDoc");
                });
    }

    private static MeUpdateRequest pedido(TipoDoc tipoDoc, String nroDoc) {
        return new MeUpdateRequest("Juan", "Pérez", LocalDate.of(1990, 5, 20), "Calle 123", "1155551234",
                tipoDoc, nroDoc);
    }

    private static Usuario usuario(Persona persona) {
        Usuario usuario = new Usuario();
        ReflectionTestUtils.setField(usuario, "id", ID);
        usuario.setUsername("jperez");
        usuario.setEmail("jperez@example.com");
        usuario.setPasswordHash("$2a$12$hash");
        usuario.setEstado(EstadoUsuario.ACTIVO);
        usuario.setPersona(persona);
        return usuario;
    }

    private static Persona personaCompleta() {
        Persona persona = personaSinDocumento();
        persona.setNombres("Juan");
        persona.setApellidos("Pérez");
        persona.setTipoDoc("DNI");
        persona.setNroDoc("30123456");
        persona.setFechaNacimiento(LocalDate.of(1990, 5, 20));
        persona.setDomicilioPostal("Calle 123");
        persona.setTelefono("1155551234");
        return persona;
    }

    private static Persona personaSinDocumento() {
        Persona persona = new Persona();
        ReflectionTestUtils.setField(persona, "id", 10L);
        persona.setNombres("Juan");
        persona.setApellidos("Pérez");
        persona.setEstado(EstadoGeneral.ACTIVO);
        return persona;
    }

    private static Rol rol(Long id, String nombre) {
        Rol rol = new Rol();
        ReflectionTestUtils.setField(rol, "id", id);
        rol.setNombre(nombre);
        rol.setNombreAmigable(nombre);
        rol.setEstado(EstadoGeneral.ACTIVO);
        return rol;
    }
}
