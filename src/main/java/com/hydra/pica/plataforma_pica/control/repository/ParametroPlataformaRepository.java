package com.hydra.pica.plataforma_pica.control.repository;

import java.util.List;

import com.hydra.pica.plataforma_pica.control.domain.ParametroPlataforma;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ParametroPlataformaRepository extends JpaRepository<ParametroPlataforma, String> {

    /** Todos, bloqueados: las reglas entre parámetros (gastos y tope) se validan sin que otro los cambie a la vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ParametroPlataforma p")
    List<ParametroPlataforma> bloquearTodos();
}
