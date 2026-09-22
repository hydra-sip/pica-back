package com.hydra.pica.plataforma_pica.user.service;

import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;

/**
 * Entrada de {@link UsuarioService#crear}. Hay tres formas de que aparezca un usuario y cada una
 * tiene sus defaults, por eso no se construye a mano sino con una de las fábricas:
 *
 * <ul>
 *   <li>{@link #autoRegistro}: el formulario público. Nace PENDIENTE_VERIFICACION, sin mail
 *       verificado y con el rol PARTICIPANTE. La persona se busca o crea por documento.</li>
 *   <li>{@link #porAdmin}: alta desde el ABM. Nace ACTIVO (o BLOQUEADO si el admin lo pide) con el
 *       mail ya verificado; la persona tiene que existir y los roles van por id.</li>
 *   <li>{@link #desdeGoogle}: primer login con Google. ACTIVO y verificado, sin contraseña y sin
 *       documento; el username se genera a partir del mail. También PARTICIPANTE.</li>
 * </ul>
 *
 * La persona viene de una de dos formas: {@code datosPersona} (registro y Google) o
 * {@code personaId} (admin). Nunca las dos.
 */
public record NuevoUsuario(
        Origen origen,
        String username,
        String email,
        String password,
        String googleSub,
        String descripcion,
        EstadoUsuario estadoInicial,
        boolean emailVerificado,
        DatosPersona datosPersona,
        Long personaId,
        Set<Long> rolIds
) {

    public enum Origen {
        AUTO_REGISTRO,
        ADMIN,
        GOOGLE
    }

    public NuevoUsuario {
        if (origen == null || estadoInicial == null) {
            throw new IllegalArgumentException("origen y estadoInicial son obligatorios");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email es obligatorio");
        }
        if ((datosPersona == null) == (personaId == null)) {
            throw new IllegalArgumentException("Va datosPersona o personaId, uno de los dos");
        }
        if (origen == Origen.GOOGLE) {
            if (googleSub == null || googleSub.isBlank()) {
                throw new IllegalArgumentException("Un usuario de Google necesita googleSub");
            }
        } else if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Registro y alta por admin necesitan contraseña");
        }
        email = email.strip();
        username = username == null ? null : username.strip();
        rolIds = rolIds == null ? Set.of() : Set.copyOf(rolIds);
    }

    public static NuevoUsuario autoRegistro(String username, String email, String password, DatosPersona persona) {
        return new NuevoUsuario(Origen.AUTO_REGISTRO, username, email, password, null, null,
                EstadoUsuario.PENDIENTE_VERIFICACION, false, persona, null, Set.of());
    }

    public static NuevoUsuario porAdmin(String username, String email, String passwordTemporal, String descripcion,
                                        EstadoUsuario estado, Long personaId, Set<Long> rolIds) {
        return new NuevoUsuario(Origen.ADMIN, username, email, passwordTemporal, null, descripcion,
                estado == null ? EstadoUsuario.ACTIVO : estado, true, null, personaId, rolIds);
    }

    public static NuevoUsuario desdeGoogle(String email, String googleSub, String nombres, String apellidos) {
        return new NuevoUsuario(Origen.GOOGLE, null, email, null, googleSub, null,
                EstadoUsuario.ACTIVO, true, DatosPersona.sinDocumento(nombres, apellidos), null, Set.of());
    }

    /** Registro y Google no eligen roles: el servicio les pone PARTICIPANTE. */
    public boolean llevaRolParticipante() {
        return origen != Origen.ADMIN;
    }
}
