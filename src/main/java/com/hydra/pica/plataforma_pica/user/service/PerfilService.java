package com.hydra.pica.plataforma_pica.user.service;

import java.util.List;
import java.util.Locale;

import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.ConflictoException;
import com.hydra.pica.plataforma_pica.common.error.ErrorCampo;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.TipoDoc;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.CambioPasswordRequest;
import com.hydra.pica.plataforma_pica.user.dto.Me;
import com.hydra.pica.plataforma_pica.user.dto.MeUpdateRequest;
import com.hydra.pica.plataforma_pica.user.event.SesionesDeUsuarioInvalidadas;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mi perfil (PICA-121): GET y PUT /me. Recibe el id del usuario logueado; sacarlo del token es
 * cosa del controller. Email y username no se editan en la E2; la contraseña va por PUT /me/password.
 */
@Service
@RequiredArgsConstructor
public class PerfilService {

    private final UsuarioRepository usuarioRepository;
    private final PermisoService permisoService;
    private final PersonaService personaService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventos;

    @Transactional(readOnly = true)
    public Me obtener(Long usuarioId) {
        Usuario usuario = buscar(usuarioId);
        return Me.desde(usuario, permisoService.permisosDe(usuarioId));
    }

    /**
     * Reemplaza los datos de la persona: un opcional en null se borra. El documento es aparte:
     * si no viene se deja el que está, si la persona no tenía se carga, y si ya tenía uno solo se
     * acepta el mismo (el front manda el formulario entero). Tipo y número van juntos o ninguno.
     */
    @Transactional
    public Me actualizar(Long usuarioId, MeUpdateRequest request) {
        Usuario usuario = buscar(usuarioId);
        Persona persona = usuario.getPersona();

        persona.setNombres(request.nombres().strip());
        persona.setApellidos(request.apellidos().strip());
        persona.setFechaNacimiento(request.fechaNacimiento());
        persona.setDomicilioPostal(textoONull(request.domicilioPostal()));
        persona.setTelefono(textoONull(request.telefono()));

        String nroDoc = textoONull(request.nroDoc());
        actualizarDocumento(persona, request.tipoDoc(), nroDoc == null ? null : nroDoc.toUpperCase(Locale.ROOT));

        return Me.desde(usuario, permisoService.permisosDe(usuarioId));
    }

    /**
     * Cambia la contraseña del propio usuario (PICA-122). Si ya tiene, hay que confirmar la actual; si no
     * (entró con Google) define la primera sin actual. Después se publica
     * {@link SesionesDeUsuarioInvalidadas}: quien maneja los refresh tokens los revoca todos, así que el front
     * tiene que volver a loguear (el contrato dice "todos", no "los demás").
     */
    @Transactional
    public void cambiarPassword(Long usuarioId, CambioPasswordRequest request) {
        Usuario usuario = buscar(usuarioId);

        if (usuario.getPasswordHash() != null) {
            String actual = request.passwordActual();
            if (actual == null || actual.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                        .con("errores", List.of(new ErrorCampo("passwordActual", ErrorCampo.Codigo.REQUERIDO,
                                "Hay que indicar la contraseña actual")));
            }
            if (!passwordEncoder.matches(actual, usuario.getPasswordHash())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.PASSWORD_ACTUAL_INCORRECTA,
                        "La contraseña actual no es correcta");
            }
        }

        usuario.setPasswordHash(passwordEncoder.encode(request.passwordNueva()));
        usuarioRepository.saveAndFlush(usuario);
        eventos.publishEvent(new SesionesDeUsuarioInvalidadas(usuarioId));
    }

    private void actualizarDocumento(Persona persona, TipoDoc tipoDoc, String nroDoc) {
        if ((tipoDoc == null) != (nroDoc == null)) {
            String falta = tipoDoc == null ? "tipoDoc" : "nroDoc";
            throw new ApiException(HttpStatus.BAD_REQUEST, CodigoError.VALIDACION, "Hay campos inválidos")
                    .con("errores", List.of(new ErrorCampo(
                            falta, ErrorCampo.Codigo.REQUERIDO, "tipoDoc y nroDoc van juntos: los dos o ninguno")));
        }
        if (tipoDoc == null) {
            return;
        }

        if (persona.getTipoDoc() == null) {
            personaService.asignarDocumento(persona, tipoDoc, nroDoc);
            return;
        }

        boolean mismo = tipoDoc.name().equals(persona.getTipoDoc()) && nroDoc.equalsIgnoreCase(persona.getNroDoc());
        if (!mismo) {
            throw new ConflictoException(CodigoError.DOCUMENTO_NO_EDITABLE,
                    "El documento ya está cargado; para corregirlo hay que pedírselo a un administrador");
        }
    }

    /**
     * El filtro JWT solo deja pasar tokens válidos, pero el usuario pudo ser dado de baja después de
     * emitido el token: para /me eso es "no hay sesión", no un 404.
     */
    private Usuario buscar(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, CodigoError.NO_AUTENTICADO,
                        "El usuario de la sesión ya no existe"));
        // el token puede seguir vigente aunque el estado haya cambiado desde que se emitió: mismos
        // códigos que el login
        switch (usuario.getEstado()) {
            case BLOQUEADO -> throw new ApiException(HttpStatus.FORBIDDEN, CodigoError.USUARIO_BLOQUEADO,
                    "El usuario está bloqueado");
            case PENDIENTE_VERIFICACION -> throw new ApiException(HttpStatus.FORBIDDEN,
                    CodigoError.EMAIL_NO_VERIFICADO, "El email todavía no fue verificado");
            case ACTIVO -> { }
        }
        return usuario;
    }

    private static String textoONull(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return valor.strip();
    }
}
