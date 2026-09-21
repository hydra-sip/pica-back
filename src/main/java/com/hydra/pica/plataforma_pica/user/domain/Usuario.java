package com.hydra.pica.plataforma_pica.user.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "usuario")
@SQLRestriction("eliminado_en IS NULL")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Usuario extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "username", length = 50, nullable = false)
    private String username;

    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "google_sub", length = 255)
    private String googleSub;

    @Column(name = "token_verificacion_hash", length = 64)
    private String tokenVerificacionHash;

    @Column(name = "token_verificacion_expira_en")
    private Instant tokenVerificacionExpiraEn;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 30, nullable = false)
    private EstadoUsuario estado;

    @Column(name = "email_verificado", nullable = false)
    private boolean emailVerificado;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "persona_id", nullable = false)
    private Persona persona;

    @OneToMany(mappedBy = "usuario", fetch = FetchType.LAZY)
    private Set<UsuarioRol> roles = new HashSet<>();

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;

    @Override
    public String toString() {
        return "Usuario{"
                + "id=" + id
                + ", username='" + username + '\''
                + ", email='" + email + '\''
                + ", tokenVerificacionExpiraEn=" + tokenVerificacionExpiraEn
                + ", descripcion='" + descripcion + '\''
                + ", estado=" + estado
                + ", emailVerificado=" + emailVerificado
                + ", eliminadoEn=" + eliminadoEn
                + '}';
    }
}
