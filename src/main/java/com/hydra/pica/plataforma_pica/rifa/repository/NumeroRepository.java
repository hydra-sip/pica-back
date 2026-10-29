package com.hydra.pica.plataforma_pica.rifa.repository;

import java.util.Collection;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.NumeroId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NumeroRepository extends JpaRepository<Numero, NumeroId> {

    long countByIdRifaIdAndEstado(Long rifaId, EstadoNumero estado);

    List<Numero> findByIdRifaIdAndEstado(Long rifaId, EstadoNumero estado);

    List<Numero> findByIdRifaIdAndIdNumeroIn(Long rifaId, Collection<Integer> numeros);

    /** Todos los que tienen fila (vendidos, reservados y reintegrados), en orden: los demás están libres. */
    List<Numero> findByIdRifaIdOrderByIdNumero(Long rifaId);
}
