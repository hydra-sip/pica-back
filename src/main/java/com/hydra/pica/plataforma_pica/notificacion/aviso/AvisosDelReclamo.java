package com.hydra.pica.plataforma_pica.notificacion.aviso;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.rifa.domain.EstadoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.PremioRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.ReclamoRifa;
import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
import com.hydra.pica.plataforma_pica.rifa.dto.DecisionReclamo;
import com.hydra.pica.plataforma_pica.rifa.event.FondosReclamoLiberados;
import com.hydra.pica.plataforma_pica.rifa.event.ReclamoRifaResuelto;
import com.hydra.pica.plataforma_pica.rifa.repository.NumeroRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.PremioRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.ReclamoRifaRepository;
import com.hydra.pica.plataforma_pica.rifa.repository.RifaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resultado de la revisión de un reclamo de rifa (HU-808, T808-2). Reemplaza el correo que mandaba
 * {@code AvisoResolucionReclamo}: la notificación ya sale también por correo, así nadie recibe dos.
 * <ul>
 * <li>Al resolverlo ({@link ReclamoRifaResuelto}): al reclamante, al organizador y a los ganadores del sorteo, con el
 * fallo, la justificación de soporte y la rifa (y el torneo si tiene).</li>
 * <li>Al liberar los fondos ({@link FondosReclamoLiberados}): a cada uno que recibió plata, con su monto.</li>
 * </ul>
 */
@Component
public class AvisosDelReclamo {

    private static final DecimalFormat PESOS =
            new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR")));

    private final ReclamoRifaRepository reclamoRifaRepository;
    private final RifaRepository rifaRepository;
    private final PremioRifaRepository premioRifaRepository;
    private final NumeroRepository numeroRepository;
    private final AvisosDePartido avisos;
    private final TransactionTemplate lectura;

    AvisosDelReclamo(ReclamoRifaRepository reclamoRifaRepository, RifaRepository rifaRepository,
                     PremioRifaRepository premioRifaRepository, NumeroRepository numeroRepository,
                     AvisosDePartido avisos, PlatformTransactionManager transacciones) {
        this.reclamoRifaRepository = reclamoRifaRepository;
        this.rifaRepository = rifaRepository;
        this.premioRifaRepository = premioRifaRepository;
        this.numeroRepository = numeroRepository;
        this.avisos = avisos;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alResolverReclamo(ReclamoRifaResuelto evento) {
        avisos.enviar(lectura.execute(estado -> resolucion(evento)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void alLiberarFondos(FondosReclamoLiberados evento) {
        avisos.enviar(lectura.execute(estado -> fondos(evento)));
    }

    private List<Aviso> resolucion(ReclamoRifaResuelto evento) {
        ReclamoRifa reclamo = reclamoRifaRepository.findDetalleById(evento.reclamoId()).orElse(null);
        if (reclamo == null) {
            return List.of();
        }
        Rifa rifa = reclamo.getRifa();
        Set<Long> destinatarios = new LinkedHashSet<>();
        destinatarios.add(reclamo.getUsuario().getId());
        destinatarios.add(rifa.getOrganizador().getId());
        destinatarios.addAll(ganadores(rifa.getId()));

        String fallo = evento.decision() == DecisionReclamo.FRAUDE_CONFIRMADO
                ? "Fraude confirmado: el reclamo fue aceptado."
                : "Sin fraude: el reclamo fue rechazado.";
        String cuerpo = "Soporte resolvió el reclamo sobre la rifa \"" + rifa.getTitulo() + "\"" + delTorneo(rifa)
                + ".\n\nFallo: " + fallo
                + "\nJustificación:\n" + evento.resolucion()
                + "\n\nEsta resolución cierra el proceso de disputa y no admite apelaciones.";
        Map<String, Object> datos = datos(evento.reclamoId(), rifa);
        datos.put("decision", evento.decision().name());

        List<Aviso> todos = new ArrayList<>();
        for (Long usuarioId : destinatarios) {
            todos.add(new Aviso(usuarioId, TipoNotificacion.RECLAMO_RESUELTO,
                    "Resolución del reclamo en la rifa " + rifa.getTitulo(), cuerpo, "/rifas/" + rifa.getId(), datos));
        }
        return todos;
    }

    private List<Aviso> fondos(FondosReclamoLiberados evento) {
        Rifa rifa = rifaRepository.findById(evento.rifaId()).orElse(null);
        if (rifa == null || evento.acreditados() == null) {
            return List.of();
        }
        boolean reintegro = evento.rifaEstadoFinal() == EstadoRifa.CANCELADA;
        List<Aviso> todos = new ArrayList<>();
        evento.acreditados().forEach((usuarioId, monto) -> {
            String pesos = "$ " + PESOS.format(monto);
            String cuerpo = reintegro
                    ? "Por el fraude confirmado en la rifa \"" + rifa.getTitulo() + "\"" + delTorneo(rifa)
                            + " la rifa se canceló y te devolvimos lo que pagaste: " + pesos + " en tu billetera."
                    : "Se liberaron los fondos de la rifa \"" + rifa.getTitulo() + "\"" + delTorneo(rifa)
                            + " tras la resolución del reclamo: se acreditaron " + pesos + " en tu billetera.";
            Map<String, Object> datos = datos(evento.reclamoId(), rifa);
            datos.put("monto", monto);
            todos.add(new Aviso(usuarioId, TipoNotificacion.RECLAMO_RESUELTO,
                    "Se acreditaron " + pesos + " en tu billetera", cuerpo, "/billetera", datos));
        });
        return todos;
    }

    // el comprador del número de cada premio sorteado
    private List<Long> ganadores(Long rifaId) {
        List<Integer> numeros = premioRifaRepository.buscarPorRifa(rifaId).stream()
                .map(PremioRifa::getNumeroGanador)
                .filter(Objects::nonNull)
                .toList();
        if (numeros.isEmpty()) {
            return List.of();
        }
        return numeroRepository.findByIdRifaIdAndIdNumeroIn(rifaId, numeros).stream()
                .map(Numero::getTicket)
                .filter(Objects::nonNull)
                .map(ticket -> ticket.getComprador().getId())
                .toList();
    }

    private static String delTorneo(Rifa rifa) {
        return rifa.getTorneo() == null ? "" : " del torneo " + rifa.getTorneo().getNombre();
    }

    private static Map<String, Object> datos(Long reclamoId, Rifa rifa) {
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("reclamoId", reclamoId);
        datos.put("rifaId", rifa.getId());
        if (rifa.getTorneo() != null) {
            datos.put("torneoId", rifa.getTorneo().getId());
        }
        return datos;
    }
}
