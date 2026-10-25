package com.hydra.pica.plataforma_pica.user.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
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
