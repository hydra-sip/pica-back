package com.hydra.pica.plataforma_pica.user.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.EstadoGeneral;
import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Rol;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/** `eliminado_en IS NULL` ya lo impone {@code @SQLRestriction} en {@link Usuario}. */
public final class UsuarioSpecifications {

    private UsuarioSpecifications() {
    }

    public static Specification<Usuario> conTexto(String texto) {
        String patron = BusquedaSinTildes.patron(texto);
        if (patron == null) {
            return null;
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Usuario, Persona> persona = root.join("persona");
            return BusquedaSinTildes.algunaContiene(cb, patron, List.of(
                    root.get("username"), root.get("email"), persona.get("apellidos"), persona.get("nroDoc")));
        };
    }

    /**
     * Para buscar jugadores: cada palabra tiene que aparecer en el username, los nombres o los apellidos.
     * No mira email ni documento, que no se muestran.
     */
    public static Specification<Usuario> conNombreOUsername(String texto) {
        List<String> patrones = BusquedaSinTildes.patronesPorPalabra(texto);
        if (patrones.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            Join<Usuario, Persona> persona = root.join("persona");
            List<Expression<String>> columnas = List.of(
                    root.get("username"), persona.get("nombres"), persona.get("apellidos"));
            return cb.and(patrones.stream()
                    .map(patron -> BusquedaSinTildes.algunaContiene(cb, patron, columnas))
                    .toArray(Predicate[]::new));
        };
    }

    public static Specification<Usuario> conEstado(EstadoUsuario estado) {
        if (estado == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("estado"), estado);
    }

    /**
     * Con ese rol asignado y activo. Va con EXISTS y no con join para no repetir filas: así no hace falta DISTINCT,
     * que en Postgres no deja ordenar por la persona.
     */
    public static Specification<Usuario> conRolActivo(String nombreRol) {
        return (root, query, cb) -> {
            Subquery<Long> asignacion = query.subquery(Long.class);
            Root<UsuarioRol> usuarioRol = asignacion.from(UsuarioRol.class);
            Join<UsuarioRol, Rol> rol = usuarioRol.join("rol");
            asignacion.select(usuarioRol.get("id").get("usuarioId")).where(
                    cb.equal(usuarioRol.get("usuario"), root),
                    cb.equal(rol.get("nombre"), nombreRol),
                    cb.equal(rol.get("estado"), EstadoGeneral.ACTIVO));
            return cb.exists(asignacion);
        };
    }

    public static Specification<Usuario> conRol(Long rolId) {
        if (rolId == null) {
            return null;
        }
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Usuario, UsuarioRol> usuarioRol = root.join("roles");
            return cb.equal(usuarioRol.get("rol").get("id"), rolId);
        };
    }
}
