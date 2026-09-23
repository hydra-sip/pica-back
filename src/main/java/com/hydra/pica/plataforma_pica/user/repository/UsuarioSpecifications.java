package com.hydra.pica.plataforma_pica.user.repository;

import com.hydra.pica.plataforma_pica.user.domain.EstadoUsuario;
import com.hydra.pica.plataforma_pica.user.domain.Persona;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.domain.UsuarioRol;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

/** `eliminado_en IS NULL` ya lo impone {@code @SQLRestriction} en {@link Usuario}. */
public final class UsuarioSpecifications {

    private UsuarioSpecifications() {
    }

    public static Specification<Usuario> conTexto(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String patron = "%" + texto.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            query.distinct(true);
            Join<Usuario, Persona> persona = root.join("persona");
            return cb.or(
                    cb.like(cb.lower(root.get("username")), patron),
                    cb.like(cb.lower(root.get("email")), patron),
                    cb.like(cb.lower(persona.get("apellidos")), patron),
                    cb.like(cb.lower(persona.get("nroDoc")), patron));
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
