package com.hydra.pica.plataforma_pica.torneo.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Baja lógica con {@code eliminadoEn}, sin {@code @SQLRestriction}: inscripciones, partidos y
 * movimientos viejos siguen apuntando al torneo, así que cada consulta filtra los eliminados.
 */
@Entity
@Table(name = "torneo")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Torneo extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizador_id", nullable = false)
    private Usuario organizador;

    @Column(name = "nombre", length = 100, nullable = false)
    private String nombre;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "reglamento_url", length = 500, nullable = false)
    private String reglamentoUrl;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reglamento_hash", length = 64)
    private String reglamentoHash;

    @Column(name = "disciplina", length = 50, nullable = false)
    private String disciplina;

    @Enumerated(EnumType.STRING)
    @Column(name = "formato", length = 30, nullable = false)
    private Formato formato = Formato.ELIMINACION_DIRECTA;

    @Enumerated(EnumType.STRING)
    @Column(name = "modalidad", length = 20, nullable = false)
    private Modalidad modalidad;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_participante", length = 20, nullable = false)
    private TipoParticipante tipoParticipante;

    @Column(name = "sede", length = 255)
    private String sede;

    @Column(name = "cupo_maximo", nullable = false)
    private Integer cupoMaximo;

    @Column(name = "cupo_minimo", nullable = false)
    private Integer cupoMinimo;

    /** Solo en torneos por equipos. */
    @Column(name = "tamanio_equipo")
    private Integer tamanioEquipo;

    @Column(name = "valor_inscripcion", precision = 14, scale = 2, nullable = false)
    private BigDecimal valorInscripcion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "moneda", length = 3, nullable = false)
    private String moneda = "ARS";

    @Column(name = "pct_pozo_competidores", precision = 5, scale = 2, nullable = false)
    private BigDecimal pctPozoCompetidores;

    /** % de lo recaudado por la rifa que se suma al premio del torneo. */
    @Column(name = "pct_premio_rifa", precision = 5, scale = 2, nullable = false)
    private BigDecimal pctPremioRifa;

    @Column(name = "cierre_inscripcion_en", nullable = false)
    private Instant cierreInscripcionEn;

    @Column(name = "fecha_inicio", nullable = false)
    private Instant fechaInicio;

    @Column(name = "fecha_fin")
    private Instant fechaFin;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 30, nullable = false)
    private EstadoTorneo estado = EstadoTorneo.BORRADOR;

    @Column(name = "motivo_cancelacion", columnDefinition = "TEXT")
    private String motivoCancelacion;

    /** Enlace público; se fija al publicar. */
    @Column(name = "slug", length = 150, unique = true)
    private String slug;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "esquema_hash", length = 64)
    private String esquemaHash;

    @Column(name = "publicado_en")
    private Instant publicadoEn;

    /** Sella la lista de inscriptos al cerrar la inscripción. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "lista_hash", length = 64)
    private String listaHash;

    @Column(name = "lista_sellada_en")
    private Instant listaSelladaEn;

    @Column(name = "semilla_bracket", length = 64)
    private String semillaBracket;

    @Column(name = "checkin_min_antes")
    private Integer checkinMinAntes;

    @Column(name = "checkin_duracion_min")
    private Integer checkinDuracionMin;

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;

    // torneo_cancha y torneo_arbitro no tienen columnas propias, como rol_permiso
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "torneo_cancha",
            joinColumns = @JoinColumn(name = "torneo_id"),
            inverseJoinColumns = @JoinColumn(name = "cancha_id"))
    private Set<Cancha> canchas = new HashSet<>();

    /**
     * Árbitros designados; el árbitro de cada partido tiene que ser uno de ellos. Hibernate inserta
     * estas filas al final del flush, después de las entidades: hay que hacer flush antes de crear
     * partidos con un árbitro recién designado (y lo mismo con las canchas).
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "torneo_arbitro",
            joinColumns = @JoinColumn(name = "torneo_id"),
            inverseJoinColumns = @JoinColumn(name = "usuario_id"))
    private Set<Usuario> arbitros = new HashSet<>();
}
