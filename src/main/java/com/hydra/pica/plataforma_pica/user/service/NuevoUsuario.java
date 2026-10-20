package com.hydra.pica.plataforma_pica.user.service;

import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;

/**
 * Entrada de {@link UsuarioService#crear}. Hay cuatro formas de que aparezca un usuario y cada una
 * tiene sus defaults, por eso no se construye a mano sino con una de las fábricas:
 *
 * <ul>
 *   <li>{@link #autoRegistro}: el formulario público. Nace PENDIENTE_VERIFICACION, sin mail
 *       verificado y con el rol PARTICIPANTE. La persona se busca o crea por documento.</li>
 *   <li>{@link #porAdmin}: alta desde el ABM. Nace ACTIVO (o BLOQUEADO si el admin lo pide) con el
 *       mail ya verificado; la persona tiene que existir y los roles van por id.</li>
 *   <li>{@link #desdeGoogle}: primer login con Google. ACTIVO y verificado, sin contraseña y sin
 *       documento; el username se genera a partir del mail. También PARTICIPANTE.</li>
 *   <li>{@link #adminDelSistema}: el Admin que crea {@link AdminInicial} al arrancar. ACTIVO y
 *       verificado, persona sin documento y el rol SUPER_USUARIO.</li>
 * </ul>
 *
 * La persona viene de una de dos formas: {@code datosPersona} (registro, Google y Admin del sistema)
 * o {@code personaId} (admin). Nunca las dos. El constructor rechaza cualquier combinación que no
 * sea una de esas cuatro, así el servicio no tiene que desconfiar de lo que recibe.
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
        GOOGLE,
        SISTEMA
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
        validarSegunOrigen(origen, username, password, googleSub, estadoInicial, emailVerificado, datosPersona,
                rolIds);
        email = email.strip();
        username = username == null ? null : username.strip();
        rolIds = rolIds == null ? Set.of() : Set.copyOf(rolIds);
    }

    /**
     * Cada origen tiene una sola forma válida y el servicio confía en eso: se fija en
     * {@code datosPersona != null} para saber si tiene que buscar o crear la persona, y en el
     * origen para saber si pone el rol PARTICIPANTE. Una mezcla (un alta por admin con
     * datosPersona, un usuario de Google con contraseña) no la detectaría nadie más abajo.
     */
    private static void validarSegunOrigen(Origen origen, String username, String password, String googleSub,
                                           EstadoUsuario estadoInicial, boolean emailVerificado,
                                           DatosPersona datosPersona, Set<Long> rolIds) {
        switch (origen) {
            case AUTO_REGISTRO -> {
                exigir(tieneTexto(username), "El registro elige su username");
                exigir(datosPersona != null, "El registro busca o crea la persona por documento, no lleva personaId");
                exigir(tieneTexto(password), "El registro necesita contraseña");
                exigir(googleSub == null, "El registro no lleva googleSub");
                exigir(estadoInicial == EstadoUsuario.PENDIENTE_VERIFICACION && !emailVerificado,
                        "El registro nace PENDIENTE_VERIFICACION y con el mail sin verificar");
                exigir(sinRoles(rolIds), "El registro no elige roles: el servicio le pone PARTICIPANTE");
            }
            case ADMIN -> {
                exigir(tieneTexto(username), "El alta por admin elige el username");
                exigir(datosPersona == null, "El alta por admin es sobre una persona que ya existe: lleva personaId");
                exigir(tieneTexto(password), "El alta por admin necesita una contraseña temporal");
                exigir(googleSub == null, "El alta por admin no lleva googleSub");
                exigir(estadoInicial != EstadoUsuario.PENDIENTE_VERIFICACION && emailVerificado,
                        "El alta por admin nace ACTIVO o BLOQUEADO y con el mail verificado");
            }
            case GOOGLE -> {
                exigir(datosPersona != null, "Google trae nombre y apellido, no un personaId");
                exigir(tieneTexto(googleSub), "Un usuario de Google necesita googleSub");
                exigir(password == null, "Un usuario de Google no tiene contraseña");
                exigir(username == null, "El username de un usuario de Google lo genera el servicio");
                exigir(estadoInicial == EstadoUsuario.ACTIVO && emailVerificado,
                        "Google ya verificó el mail: el usuario nace ACTIVO y verificado");
                exigir(sinRoles(rolIds), "Google no elige roles: el servicio le pone PARTICIPANTE");
            }
            case SISTEMA -> {
                exigir(tieneTexto(username), "El Admin del sistema usa app.admin.username");
                exigir(datosPersona != null, "El Admin del sistema crea su persona, no lleva personaId");
                exigir(tieneTexto(password), "El Admin del sistema necesita ADMIN_INITIAL_PASSWORD");
                exigir(googleSub == null, "El Admin del sistema no lleva googleSub");
                exigir(estadoInicial == EstadoUsuario.ACTIVO && emailVerificado,
                        "El Admin del sistema nace ACTIVO y con el mail verificado");
                exigir(sinRoles(rolIds), "El Admin del sistema no elige roles: el servicio le pone SUPER_USUARIO");
            }
        }
    }

    private static void exigir(boolean condicion, String mensaje) {
        if (!condicion) {
            throw new IllegalArgumentException(mensaje);
        }
    }

    private static boolean tieneTexto(String valor) {
        return valor != null && !valor.isBlank();
    }

    /**
     * Los roles solo los elige el admin. El servicio le suma PARTICIPANTE a todo lo que no sea
     * ADMIN, así que si un registro pudiera traer rolIds saldría con PARTICIPANTE más lo que
     * mandó: el día que el controller de la 119 mapee el body del request directo, eso es una
     * escalada de privilegios.
     */
    private static boolean sinRoles(Set<Long> rolIds) {
        return rolIds == null || rolIds.isEmpty();
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

    public static NuevoUsuario adminDelSistema(String username, String email, String password) {
        return new NuevoUsuario(Origen.SISTEMA, username, email, password, null, "Admin del sistema",
                EstadoUsuario.ACTIVO, true, DatosPersona.sinDocumento("Admin", "del Sistema"), null, Set.of());
    }

    /** Registro y Google no eligen roles: el servicio les pone PARTICIPANTE. */
    public boolean llevaRolParticipante() {
        return origen == Origen.AUTO_REGISTRO || origen == Origen.GOOGLE;
    }

    /** El Admin del sistema tampoco elige: sale con SUPER_USUARIO y nada más. */
    public boolean llevaRolSuperUsuario() {
        return origen == Origen.SISTEMA;
    }
}
