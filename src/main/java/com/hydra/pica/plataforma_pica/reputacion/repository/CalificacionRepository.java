package com.hydra.pica.plataforma_pica.reputacion.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import com.hydra.pica.plataforma_pica.reputacion.domain.Calificacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalificacionRepository extends JpaRepository<Calificacion, Long> {

    boolean existsByTorneoIdAndUsuarioId(Long torneoId, Long usuarioId);

    @Query("""
            select c from Calificacion c join fetch c.torneo join fetch c.usuario u join fetch u.persona
            join fetch c.organizador o join fetch o.persona
            where c.torneo.id = :torneoId""")
    List<Calificacion> delTorneo(@Param("torneoId") Long torneoId);

    @Query("""
            select c from Calificacion c join fetch c.torneo join fetch c.usuario u join fetch u.persona
            join fetch c.organizador o join fetch o.persona
            where c.id = :id""")
    Optional<Calificacion> conTodo(@Param("id") Long id);

    /** Una fila de {@link #reputaciones}. */
    interface FilaReputacion {
        Long getUsuarioId();

        Double getPromedio();

        Long getCantidad();
    }

    /** El promedio y la cantidad de calificaciones de cada usuario; los que no tienen ninguna no aparecen. */
    @Query("""
            select c.usuario.id as usuarioId, avg(c.puntaje) as promedio, count(c) as cantidad from Calificacion c
            where c.usuario.id in :usuarioIds group by c.usuario.id""")
    List<FilaReputacion> reputaciones(@Param("usuarioIds") Collection<Long> usuarioIds);

    /** De esos usuarios, los que algún organizador marcó como no confiables. */
    @Query("""
            select distinct c.usuario.id from Calificacion c
            where c.noConfiable = true and c.usuario.id in :usuarioIds""")
    List<Long> noConfiables(@Param("usuarioIds") Collection<Long> usuarioIds);
}
