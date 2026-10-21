package com.hydra.pica.plataforma_pica.partido.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Archivo que sube una de las partes del partido (la base exige que la inscripción juegue ese partido). */
@Entity
@Table(name = "evidencia")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Evidencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partido_id", nullable = false)
    private Partido partido;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inscripcion_id", nullable = false)
    private Inscripcion inscripcion;

    @Column(name = "url", length = 500, nullable = false)
    private String url;

    @Column(name = "tipo_contenido", length = 100, nullable = false)
    private String tipoContenido;

    @Column(name = "tamanio_bytes", nullable = false)
    private Long tamanioBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "hash", length = 64, nullable = false)
    private String hash;

    @CreatedDate
    @Column(name = "creado_en", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant creadoEn;

    @CreatedBy
    @Column(name = "creado_por", length = 100, updatable = false)
    @Setter(AccessLevel.NONE)
    private String creadoPor;
}
