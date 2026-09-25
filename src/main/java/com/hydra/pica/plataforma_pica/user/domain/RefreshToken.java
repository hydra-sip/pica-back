package com.hydra.pica.plataforma_pica.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expira_en", nullable = false)
    private Instant expiraEn;

    @Column(name = "revocado_en")
    private Instant revocadoEn;

    @Column(name = "reemplazado_por", length = 64)
    private String reemplazadoPor;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @PrePersist
    void alPersistir() {
        if (creadoEn == null) {
            creadoEn = Instant.now();
        }
    }
}
