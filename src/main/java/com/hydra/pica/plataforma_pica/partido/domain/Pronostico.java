package com.hydra.pica.plataforma_pica.partido.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Uno por usuario y partido, editable hasta que empieza el partido. */
@Entity
@Table(name = "pronostico")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Pronostico {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private PronosticoId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("usuarioId")
    @JoinColumn(name = "usuario_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("partidoId")
    @JoinColumn(name = "partido_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Partido partido;

    @Convert(converter = EleccionConverter.class)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "eleccion", length = 1, nullable = false)
    private Eleccion eleccion;

    @Column(name = "puntos")
    private Integer puntos;

    @CreatedDate
    @Column(name = "creado_en", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant creadoEn;

    @LastModifiedDate
    @Column(name = "modificado_en", nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant modificadoEn;

    protected Pronostico() {
    }

    public Pronostico(Usuario usuario, Partido partido, Eleccion eleccion) {
        this.usuario = usuario;
        this.partido = partido;
        this.eleccion = eleccion;
        this.id = new PronosticoId(usuario.getId(), partido.getId());
    }
}
