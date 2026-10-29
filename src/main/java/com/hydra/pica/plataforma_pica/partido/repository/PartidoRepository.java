package com.hydra.pica.plataforma_pica.partido.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartidoRepository extends JpaRepository<Partido, Long> {

    /** Si tiene algún partido del torneo asignado, jugado o no: la FK no deja quitarlo (PICA-237). */
    boolean existsByTorneoIdAndArbitroId(Long torneoId, Long arbitroId);

    /** El bracket, con el árbitro y la cancha, por ronda y posición. */
    @Query("select p from Partido p join fetch p.arbitro a left join fetch a.persona left join fetch p.cancha "
            + "where p.torneo.id = :torneoId order by p.ronda, p.posicion")
    List<Partido> delTorneo(@Param("torneoId") Long torneoId);

    /** Para cargar el resultado y aceptarlo: uno a la vez por partido. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Partido p where p.id = :id")
    Optional<Partido> buscarBloqueado(@Param("id") Long id);

    /** Donde sigue el ganador: el partido de la ronda siguiente que le toca. */
    Optional<Partido> findByTorneoIdAndRondaAndPosicion(Long torneoId, int ronda, int posicion);

    /** Los que tienen un resultado cargado que nadie objetó y cuyo plazo ya venció: se confirman solos. */
    @Query("""
            select p.id from Partido p
            where p.estado = com.hydra.pica.plataforma_pica.partido.domain.EstadoPartido.RESULTADO_CARGADO
              and p.objecionVenceEn <= :ahora
            order by p.objecionVenceEn""")
    List<Long> conPlazoVencido(@Param("ahora") Instant ahora);

    /**
     * Los que pueden tener una ausencia (HU-309): PROGRAMADO o CHECK_IN, con fecha y con los dos lados, de un torneo
     * EN_CURSO con el check-in configurado, cuya ventana cerró antes de {@code limite} (ahora menos la tolerancia).
     */
    @Query(value = """
            select p.id from partido p join torneo t on t.id = p.torneo_id
            where t.estado = 'EN_CURSO' and t.checkin_min_antes is not null
              and p.estado in ('PROGRAMADO', 'CHECK_IN') and p.fecha_programada is not null
              and (select count(*) from juega j where j.partido_id = p.id) = 2
              and p.fecha_programada - (t.checkin_min_antes - t.checkin_duracion_min) * interval '1 minute' < :limite
            order by p.fecha_programada, p.id""", nativeQuery = true)
    List<Long> conVentanaVencida(@Param("limite") Instant limite);

    /** Cuántas rondas tiene el bracket: con eso se nombra cada una ("Final", "Semifinal"...). */
    @Query("select coalesce(max(p.ronda), 0) from Partido p where p.torneo.id = :torneoId")
    int rondasDelTorneo(@Param("torneoId") Long torneoId);

    /**
     * Los partidos sin cerrar del torneo en los que juega el usuario, como jugador inscripto o como integrante del
     * equipo inscripto, de la primera ronda a la última.
     */
    @Query("""
            select p from Partido p
            where p.torneo.id = :torneoId and p.estado not in :cerrados
              and exists (select j from Juega j join j.inscripcion i
                          where j.partido = p
                            and (i.usuario.id = :usuarioId
                                 or exists (select ue from UsuarioEquipo ue
                                            where ue.equipo = i.equipo and ue.usuario.id = :usuarioId
                                              and ue.eliminadoEn is null)))
            order by p.ronda, p.posicion""")
    List<Partido> abiertosDe(@Param("torneoId") Long torneoId, @Param("usuarioId") Long usuarioId,
                             @Param("cerrados") Collection<EstadoPartido> cerrados);

    /** Para regenerar el bracket; antes hay que borrar los lados ({@link JuegaRepository#borrarDelTorneo}). */
    @Modifying(flushAutomatically = true)
    @Query("delete from Partido p where p.torneo.id = :torneoId")
    void borrarDelTorneo(@Param("torneoId") Long torneoId);

    /** Partidos de torneos visibles para pronósticos. */
    @Query("""
            select p from Partido p join fetch p.torneo t
            where t.eliminadoEn is null and t.estado != com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo.BORRADOR
              and (:torneoId is null or t.id = :torneoId)
            order by t.id, p.ronda, p.posicion""")
    List<Partido> paraPronosticos(@Param("torneoId") Long torneoId);
}
