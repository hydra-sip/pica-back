package com.hydra.pica.plataforma_pica.common.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AlmacenamientoConfig.AlmacenamientoProperties.class)
public class AlmacenamientoConfig {

    /**
     * Dónde se guardan los archivos que se suben, como el PDF del reglamento (PICA-200).
     *
     * @param tipo {@code local} (por defecto) o {@code supabase}
     * @param local la carpeta; sin valor, {@code pica-archivos} en la carpeta temporal del sistema
     * @param supabase el proyecto, la clave service_role y el bucket (privado: los archivos los sirve la API)
     */
    @ConfigurationProperties(prefix = "app.almacenamiento")
    public record AlmacenamientoProperties(@DefaultValue("local") String tipo, @DefaultValue Local local,
            @DefaultValue Supabase supabase) {

        public record Local(String directorio) {

            public Path carpeta() {
                return directorio == null || directorio.isBlank()
                        ? Path.of(System.getProperty("java.io.tmpdir"), "pica-archivos")
                        : Path.of(directorio);
            }
        }

        public record Supabase(String url, String clave, @DefaultValue("reglamentos") String bucket) {
        }
    }
}
