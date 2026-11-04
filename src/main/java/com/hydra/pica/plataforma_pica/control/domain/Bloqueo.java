package com.hydra.pica.plataforma_pica.control.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Con {@code hasta} null es indefinido y lo levanta soporte; con fecha se levanta solo. Alcance
 * USUARIO no deja iniciar sesión; BILLETERA solo frena retiros.
 */
@Entity
@Table(name = "bloqueo")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Bloqueo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(name = "alcance", length = 20, nullable = false)
    private AlcanceBloqueo alcance;

    @Column(name = "motivo", columnDefinition = "TEXT", nullable = false)
    private String motivo;

    @Column(name = "desde", nullable = false)
    private Instant desde;

    @Column(name = "hasta")
    private Instant hasta;

    @Column(name = "levantado_en")
    private Instant levantadoEn;

    @Column(name = "levantado_por", length = 100)
    private String levantadoPor;

    @CreatedDate
    @Column(name = "creado_en", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant creadoEn;

    @CreatedBy
    @Column(name = "creado_por", length = 100, updatable = false)
    @Setter(AccessLevel.NONE)
    private String creadoPor;

    @PrePersist
    void alPersistir() {
        if (desde == null) {
            desde = Instant.now();
        }
    }
}
