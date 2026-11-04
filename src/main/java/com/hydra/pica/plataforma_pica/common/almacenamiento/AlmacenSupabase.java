package com.hydra.pica.plataforma_pica.common.almacenamiento;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.common.config.AlmacenamientoConfig.AlmacenamientoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * En un bucket de Supabase Storage, por su API REST. El bucket es privado y se entra con la clave service_role, que
 * no sale del back: los archivos los sirve la API. Para Storage un archivo que no existe es un 400 o un 404.
 */
@Component
@ConditionalOnProperty(name = "app.almacenamiento.tipo", havingValue = "supabase")
class AlmacenSupabase implements AlmacenArchivos {

    private final RestClient http;
    private final String bucket;

    AlmacenSupabase(AlmacenamientoProperties properties, RestClient.Builder builder) {
        AlmacenamientoProperties.Supabase supabase = properties.supabase();
        if (vacio(supabase.url()) || vacio(supabase.clave())) {
            throw new IllegalStateException(
                    "Con app.almacenamiento.tipo=supabase faltan APP_ALMACENAMIENTO_SUPABASE_URL o _CLAVE");
        }
        this.http = builder.baseUrl(supabase.url().replaceAll("/+$", "") + "/storage/v1/object")
                .defaultHeader("apikey", supabase.clave())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + supabase.clave())
                .build();
        this.bucket = supabase.bucket();
    }

    @Override
    public void guardar(String clave, byte[] contenido, String tipoContenido) {
        http.post()
                .uri("/" + bucket + "/" + clave)
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(tipoContenido))
                .body(contenido)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public Optional<byte[]> leer(String clave) {
        return http.get()
                .uri("/authenticated/" + bucket + "/" + clave)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return Optional.of(response.getBody().readAllBytes());
                    }
                    if (noExiste(response.getStatusCode())) {
                        return Optional.empty();
                    }
                    throw new IllegalStateException("Supabase Storage respondió " + response.getStatusCode());
                });
    }

    @Override
    public void borrar(String clave) {
        http.delete()
                .uri("/" + bucket + "/" + clave)
                .retrieve()
                .onStatus(AlmacenSupabase::noExiste, (request, response) -> { })
                .toBodilessEntity();
    }

    private static boolean noExiste(HttpStatusCode status) {
        return status.value() == 400 || status.value() == 404;
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
