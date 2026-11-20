package com.hydra.pica.plataforma_pica.notificacion.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executor;

import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.hydra.pica.plataforma_pica.common.error.NoEncontradoException;
import com.hydra.pica.plataforma_pica.notificacion.domain.Notificacion;
import com.hydra.pica.plataforma_pica.notificacion.domain.TipoNotificacion;
import com.hydra.pica.plataforma_pica.notificacion.dto.NotificacionDetalle;
import com.hydra.pica.plataforma_pica.notificacion.repository.NotificacionRepository;
import com.hydra.pica.plataforma_pica.realtime.service.RealtimeNotifier;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Centro de notificaciones (HU-808, T808-1). Cada aviso queda guardado para el usuario, le llega por WebSocket a
 * {@code /user/queue/notificaciones} y por correo. Se llama desde listeners AFTER_COMMIT: la entrega va en otro hilo
 * ({@code notificacionExecutor}), así el que generó el evento no espera al correo.
 */
@Service
@Slf4j
public class NotificacionService {

    private final NotificacionRepository notificacionRepository;
    private final UsuarioRepository usuarioRepository;
    private final RealtimeNotifier realtimeNotifier;
    private final EmailService emailService;
    private final Executor executor;
    private final String webUrl;

    public NotificacionService(
            NotificacionRepository notificacionRepository,
            UsuarioRepository usuarioRepository,
            RealtimeNotifier realtimeNotifier,
            EmailService emailService,
            @Qualifier("notificacionExecutor") Executor executor,
            @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.notificacionRepository = notificacionRepository;
        this.usuarioRepository = usuarioRepository;
        this.realtimeNotifier = realtimeNotifier;
        this.emailService = emailService;
        this.executor = executor;
        this.webUrl = webUrl;
    }

    /** {@code enlace} es la ruta del front sin dominio ({@code /torneos/copa}); el correo le suma la URL de la web. */
    public void notificar(Long usuarioId, TipoNotificacion tipo, String titulo, String cuerpo, String enlace,
                          Map<String, Object> datos) {
        executor.execute(() -> entregar(usuarioId, tipo, titulo, cuerpo, enlace, datos));
    }

    private void entregar(Long usuarioId, TipoNotificacion tipo, String titulo, String cuerpo, String enlace,
                          Map<String, Object> datos) {
        Notificacion notificacion = new Notificacion();
        notificacion.setUsuarioId(usuarioId);
        notificacion.setTipo(tipo);
        notificacion.setTitulo(titulo);
        notificacion.setCuerpo(cuerpo);
        notificacion.setEnlace(enlace);
        notificacion.setDatos(datos);
        NotificacionDetalle detalle = NotificacionDetalle.desde(notificacionRepository.save(notificacion));

        // primero la web, que es lo que se mide en los 5 segundos; el correo puede tardar
        try {
            realtimeNotifier.publicarUsuario(usuarioId, detalle);
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar la notificación {} al usuario {}", detalle.id(), usuarioId, e);
        }
        usuarioRepository.findById(usuarioId).ifPresent(usuario -> {
            String texto = enlace == null ? cuerpo : cuerpo + "\n\nVer en PICA: " + webUrl + enlace;
            try {
                emailService.enviarAviso(usuario.getEmail(), titulo, texto);
            } catch (RuntimeException e) {
                log.warn("No se pudo mandar por correo la notificación {} a {}", detalle.id(), usuario.getEmail(), e);
            }
        });
    }

    /** Las del usuario, de la más nueva a la más vieja; con {@code soloNoLeidas}, las que faltan leer. */
    @Transactional(readOnly = true)
    public Page<NotificacionDetalle> listar(Long usuarioId, boolean soloNoLeidas, int page, int size) {
        PageRequest pagina = PageRequest.of(page, size);
        Page<Notificacion> notificaciones = soloNoLeidas
                ? notificacionRepository.findByUsuarioIdAndLeidaEnIsNullOrderByCreadaEnDescIdDesc(usuarioId, pagina)
                : notificacionRepository.findByUsuarioIdOrderByCreadaEnDescIdDesc(usuarioId, pagina);
        return notificaciones.map(NotificacionDetalle::desde);
    }

    /** Idempotente. La de otro usuario da 404, igual que una que no existe. */
    @Transactional
    public void marcarLeida(Long usuarioId, Long id) {
        Notificacion notificacion = notificacionRepository.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(() -> new NoEncontradoException(CodigoError.NOTIFICACION_NO_ENCONTRADA,
                        "No existe la notificación " + id));
        if (notificacion.getLeidaEn() == null) {
            notificacion.setLeidaEn(Instant.now());
        }
    }

    @Transactional
    public void marcarTodasLeidas(Long usuarioId) {
        notificacionRepository.marcarTodasLeidas(usuarioId, Instant.now());
    }
}
