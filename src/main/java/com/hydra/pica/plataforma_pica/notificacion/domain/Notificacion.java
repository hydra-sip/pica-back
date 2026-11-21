package com.hydra.pica.plataforma_pica.notificacion.domain;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Un aviso del centro de notificaciones (HU-808). Lo único que cambia después de creado es cuándo se leyó. */
@Entity
@Table(name = "notificacion")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Notificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private Long usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", length = 30, nullable = false, updatable = false)
    private TipoNotificacion tipo;

    @Column(name = "titulo", length = 200, nullable = false, updatable = false)
    private String titulo;

    @Column(name = "cuerpo", nullable = false, updatable = false)
    private String cuerpo;

    @Column(name = "enlace", length = 255, updatable = false)
    private String enlace;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos", columnDefinition = "JSONB", updatable = false)
    private Map<String, Object> datos;

    @Column(name = "leida_en")
    private Instant leidaEn;

    @Column(name = "creada_en", nullable = false, updatable = false)
    private Instant creadaEn;

    @PrePersist
    void alPersistir() {
        if (creadaEn == null) {
            creadaEn = Instant.now();
        }
    }
}
