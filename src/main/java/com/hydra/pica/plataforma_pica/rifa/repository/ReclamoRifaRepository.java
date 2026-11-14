package com.hydra.pica.plataforma_pica.rifa.repository;

import java.util.List;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReclamoRifaRepository extends JpaRepository<ReclamoRifa, Long> {

    List<ReclamoRifa> findByRifaIdOrderByCreadoEnDesc(Long rifaId);

    boolean existsByRifaIdAndUsuarioIdAndEstado(Long rifaId, Long usuarioId, EstadoReclamo estado);

    boolean existsByRifaIdAndEstado(Long rifaId, EstadoReclamo estado);

    long countByRifaIdAndEstado(Long rifaId, EstadoReclamo estado);

    @Query("select r.rifa.id from ReclamoRifa r where r.id = :id")
    Optional<Long> findRifaIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReclamoRifa r where r.id = :id")
    Optional<ReclamoRifa> buscarBloqueado(@Param("id") Long id);

    @Query("""
            select r from ReclamoRifa r
            join fetch r.rifa rifa
            left join fetch rifa.torneo
            join fetch r.usuario
            where r.id = :id
            """)
    Optional<ReclamoRifa> findDetalleById(@Param("id") Long id);

    @Query(
            value = """
                    select r from ReclamoRifa r
                    join fetch r.rifa rifa
                    left join fetch rifa.torneo
                    join fetch r.usuario
                    order by case when r.estado = com.hydra.pica.plataforma_pica.rifa.domain.EstadoReclamo.PENDIENTE
                                  then 0 else 1 end asc,
                             r.creadoEn desc,
                             r.id desc
                    """,
            countQuery = "select count(r) from ReclamoRifa r"
    )
    Page<ReclamoRifa> buscarTodosParaAdmin(Pageable pageable);

    @Query(
            value = """
                    select r from ReclamoRifa r
                    join fetch r.rifa rifa
                    left join fetch rifa.torneo
                    join fetch r.usuario
                    where r.estado = :estado
                    order by r.creadoEn desc, r.id desc
                    """,
            countQuery = "select count(r) from ReclamoRifa r where r.estado = :estado"
    )
    Page<ReclamoRifa> buscarPorEstadoParaAdmin(@Param("estado") EstadoReclamo estado, Pageable pageable);
}

