package com.hydra.pica.plataforma_pica.billetera.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.hydra.pica.plataforma_pica.billetera.domain.CargaCripto;
import com.hydra.pica.plataforma_pica.billetera.domain.EstadoCargaCripto;
import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas;
import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas.Estado;
import com.hydra.pica.plataforma_pica.billetera.dto.ConciliacionCargas.Registro;
import com.hydra.pica.plataforma_pica.billetera.repository.CargaCriptoRepository;
import com.hydra.pica.plataforma_pica.billetera.service.EventosDelContrato.Evento;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La conciliación de las cargas con cripto (HU-608, PICA-343): lo que el back acreditó contra los
 * {@code CargaRegistrada} del contrato, cruzados por la referencia. Una carga acreditada se empareja con el evento de su
 * misma transacción o, si no, con el primero de su referencia; lo que sobra de la cadena es un pago no acreditado (por
 * ejemplo, una referencia pagada dos veces).
 */
@Service
@RequiredArgsConstructor
public class ConciliacionService {

    private final CargaCriptoRepository cargaCriptoRepository;
    private final EventosDelContrato eventosDelContrato;
    private final CriptoProperties cripto;

    @Transactional(readOnly = true)
    public ConciliacionCargas conciliar() {
        List<Evento> eventos = eventosDelContrato.cargasRegistradas();
        List<CargaCripto> cargas = cargaCriptoRepository.todasConUsuarioYMovimiento();
        List<Registro> registros = conciliar(cargas, eventos);
        return new ConciliacionCargas(Instant.now(), cripto.bloqueDeploy(), eventos.size(),
                (int) cargas.stream().filter(c -> c.getEstado() == EstadoCargaCripto.ACREDITADA).count(),
                cuantas(registros, Estado.CONCILIADA), cuantas(registros, Estado.HUERFANA),
                cuantas(registros, Estado.NO_ACREDITADA), cuantas(registros, Estado.MONTO_DISTINTO), registros);
    }

    /** Primero las diferencias y después las conciliadas; dentro de cada grupo, por bloque. */
    static List<Registro> conciliar(List<CargaCripto> cargas, List<Evento> eventos) {
        Map<String, List<Evento>> porReferencia = new LinkedHashMap<>();
        for (Evento evento : eventos) {
            porReferencia.computeIfAbsent(evento.referencia(), r -> new ArrayList<>()).add(evento);
        }
        Map<String, CargaCripto> pendientes = new HashMap<>();
        List<Registro> registros = new ArrayList<>();

        for (CargaCripto carga : cargas) {
            String referencia = minuscula(carga.getReferencia());
            if (carga.getEstado() != EstadoCargaCripto.ACREDITADA) {
                pendientes.put(referencia, carga);
                continue;
            }
            List<Evento> deLaReferencia = porReferencia.getOrDefault(referencia, new ArrayList<>());
            Optional<Evento> evento = deLaReferencia.stream()
                    .filter(e -> e.hash().equals(minuscula(carga.getHash())))
                    .findFirst()
                    .or(() -> deLaReferencia.stream().findFirst());
            evento.ifPresent(deLaReferencia::remove);
            Estado estado = evento.isEmpty() ? Estado.HUERFANA
                    : evento.get().monto().equals(carga.getMontoWei()) ? Estado.CONCILIADA : Estado.MONTO_DISTINTO;
            registros.add(registro(estado, referencia, carga, evento.orElse(null)));
        }
        porReferencia.forEach((referencia, sobrantes) -> sobrantes.forEach(evento ->
                registros.add(registro(Estado.NO_ACREDITADA, referencia, pendientes.get(referencia), evento))));

        registros.sort(Comparator.comparing((Registro r) -> r.estado() == Estado.CONCILIADA)
                .thenComparing(Registro::bloque, Comparator.nullsFirst(Comparator.naturalOrder())));
        return registros;
    }

    private static Registro registro(Estado estado, String referencia, CargaCripto carga, Evento evento) {
        return new Registro(estado, referencia,
                carga == null ? null : carga.getId(),
                carga == null ? null : carga.getUsuario().getUsername(),
                carga == null || carga.getMovimiento() == null ? null : carga.getMovimiento().getId(),
                carga == null ? null : carga.getMontoPesos(),
                carga == null ? null : carga.getMontoWei().toString(),
                evento == null ? null : evento.monto().toString(),
                carga == null ? null : minuscula(carga.getHash()),
                evento == null ? null : evento.hash(),
                evento == null ? null : evento.bloque(),
                evento != null ? evento.pagador() : carga == null ? null : minuscula(carga.getPagador()));
    }

    private static int cuantas(List<Registro> registros, Estado estado) {
        return (int) registros.stream().filter(r -> r.estado() == estado).count();
    }

    // la referencia y el hash se guardan como CHAR(66): por las dudas, sin espacios y en minúscula, como los eventos
    private static String minuscula(String hex) {
        return hex == null ? null : hex.trim().toLowerCase(Locale.ROOT);
    }
}
