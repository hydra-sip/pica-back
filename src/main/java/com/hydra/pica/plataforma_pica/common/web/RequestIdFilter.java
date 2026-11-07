package com.hydra.pica.plataforma_pica.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Le da un id a cada request (RNF-OBS-01): el del header {@code X-Request-Id} si el pedido lo manda y es válido,
 * si no un UUID nuevo. Lo deja en el MDC para que salga en cada línea de log ({@code %X{requestId}} en
 * logging.pattern.console) y lo devuelve en el mismo header.
 *
 * Va primero, antes de la cadena de Spring Security, para que también tengan id los 401/403 del filtro JWT.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Lo que se acepta del cliente: sin saltos de línea ni espacios, que permitirían falsear líneas de log. */
    private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = idDe(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        // antes de seguir: después el body ya puede estar escrito y no se agregan headers
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat reusa el hilo: sin esto el próximo request arrancaría con este id
            MDC.remove(MDC_KEY);
        }
    }

    /** Agrega el {@code requestId} del request actual, para que el front lo muestre al reportar un error. */
    public static void agregarA(ProblemDetail problema) {
        String requestId = MDC.get(MDC_KEY);
        if (requestId != null) {
            problema.setProperty(MDC_KEY, requestId);
        }
    }

    private static String idDe(String header) {
        return header != null && VALIDO.matcher(header).matches() ? header : UUID.randomUUID().toString();
    }
}
