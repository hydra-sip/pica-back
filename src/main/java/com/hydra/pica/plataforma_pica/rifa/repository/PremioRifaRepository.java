package com.hydra.pica.plataforma_pica.rifa.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifaId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PremioRifaRepository extends JpaRepository<PremioRifa, PremioRifaId> {

    @Query("SELECT p FROM PremioRifa p WHERE p.id.rifaId = :rifaId ORDER BY p.id.puesto")
    List<PremioRifa> buscarPorRifa(@Param("rifaId") Long rifaId);

    @Query("SELECT COUNT(p) FROM PremioRifa p WHERE p.id.rifaId = :rifaId")
    long contarDeRifa(@Param("rifaId") Long rifaId);

    /**
     * Borrado en bloque y no con deleteAll: Hibernate ejecuta los INSERT antes que los DELETE al hacer
     * flush, y reemplazar los premios reusa las mismas claves (rifa_id, puesto). Limpia la sesión para
     * que los premios nuevos no se mezclen con los viejos que hubieran quedado cargados.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PremioRifa p WHERE p.id.rifaId = :rifaId")
    void borrarDeRifa(@Param("rifaId") Long rifaId);
}
