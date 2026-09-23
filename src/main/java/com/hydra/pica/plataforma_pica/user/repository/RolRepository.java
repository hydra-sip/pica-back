package com.hydra.pica.plataforma_pica.user.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.user.domain.Rol;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RolRepository extends JpaRepository<Rol, Long> {

    Optional<Rol> findByNombre(String nombre);

    /** No ve registros con baja lógica; la BD sigue rechazando duplicados */
    boolean existsByNombre(String nombre);

    // Las de abajo son nativas para saltar el @SQLRestriction de Rol: el ABM de roles (PICA-125)
    // tiene que ver los dados de baja para listarlos con incluirEliminados, abrirlos, reactivarlos
    // y chequear el nombre, que es único contando a los eliminados (uq_rol_nombre de V1).

    @Query(value = "SELECT * FROM rol WHERE id = :id", nativeQuery = true)
    Optional<Rol> findByIdIncluyendoEliminados(@Param("id") Long id);

    @Query(value = "SELECT * FROM rol WHERE nombre = :nombre", nativeQuery = true)
    Optional<Rol> findByNombreIncluyendoEliminados(@Param("nombre") String nombre);

    /**
     * Listado del backoffice. {@code q} busca en nombre y nombre amigable sin distinguir
     * mayúsculas; {@code q} y {@code estado} en null no filtran. Los CAST son para que Postgres
     * sepa el tipo del parámetro cuando llega null.
     *
     * Al ser nativa, Spring agrega el ORDER BY con lo que venga en el Sort tal cual: tiene que
     * traer nombres de columna ({@code nombre_amigable}), no de propiedad. RolService los traduce.
     */
    @Query(value = """
            SELECT r.* FROM rol r
            WHERE (:incluirEliminados OR r.eliminado_en IS NULL)
              AND (CAST(:estado AS text) IS NULL OR r.estado = CAST(:estado AS text))
              AND (CAST(:q AS text) IS NULL
                   OR strpos(lower(r.nombre), lower(CAST(:q AS text))) > 0
                   OR strpos(lower(r.nombre_amigable), lower(CAST(:q AS text))) > 0)
            """,
            countQuery = """
            SELECT count(*) FROM rol r
            WHERE (:incluirEliminados OR r.eliminado_en IS NULL)
              AND (CAST(:estado AS text) IS NULL OR r.estado = CAST(:estado AS text))
              AND (CAST(:q AS text) IS NULL
                   OR strpos(lower(r.nombre), lower(CAST(:q AS text))) > 0
                   OR strpos(lower(r.nombre_amigable), lower(CAST(:q AS text))) > 0)
            """,
            nativeQuery = true)
    Page<Rol> buscar(@Param("q") String q,
                     @Param("estado") String estado,
                     @Param("incluirEliminados") boolean incluirEliminados,
                     Pageable pageable);
}
