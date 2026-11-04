package com.hydra.pica.plataforma_pica.control.repository;

import java.time.Instant;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.control.domain.AlcanceBloqueo;
import com.hydra.pica.plataforma_pica.control.domain.Bloqueo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BloqueoRepository extends JpaRepository<Bloqueo, Long> {

    /** El último bloqueo sin levantar, haya vencido su fecha de fin o no. */
    Optional<Bloqueo> findFirstByUsuarioIdAndAlcanceAndLevantadoEnIsNullOrderByDesdeDesc(
            Long usuarioId, AlcanceBloqueo alcance);

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("""
            update Bloqueo b set b.levantadoEn = :ahora, b.levantadoPor = :por
            where b.usuario.id = :usuarioId and b.alcance = :alcance and b.levantadoEn is null
            """)
    int levantarAbiertos(@Param("usuarioId") Long usuarioId, @Param("alcance") AlcanceBloqueo alcance,
            @Param("ahora") Instant ahora, @Param("por") String por);
}
