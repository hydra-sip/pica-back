package com.hydra.pica.plataforma_pica.partido.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/**
 * El sorteo del bracket de eliminación directa (HU-207, PICA-215), sin base de datos: con la misma semilla y los
 * mismos inscriptos sale siempre el mismo cuadro, así cualquiera lo puede recalcular.
 *
 * <ol>
 * <li>El orden: cada inscripción por el SHA-256 en hexadecimal de {@code semilla + ":" + inscripcionId}, de menor
 * a mayor.</li>
 * <li>El cuadro es la potencia de dos que alcanza (11 inscriptos, 16 lugares) y los lugares que sobran son BYE.</li>
 * <li>Los BYE son los primeros del orden, uno por partido de primera ronda: primero en las posiciones impares y
 * después en las pares, para que dos BYE no se crucen en la segunda ronda mientras se pueda.</li>
 * <li>Los demás llenan los partidos que quedan, en orden de posición, local y después visitante.</li>
 * </ol>
 */
public final class SorteoBracket {

    private static final SecureRandom AZAR = new SecureRandom();

    private SorteoBracket() {
    }

    /** Un partido de primera ronda: {@code visitante} es null cuando {@code local} tiene BYE. */
    public record Cruce(int posicion, long local, Long visitante) {

        public boolean bye() {
            return visitante == null;
        }
    }

    /**
     * @param rondas  cuántas rondas tiene el cuadro (con 16 lugares, 4)
     * @param cruces  los partidos de primera ronda, por posición
     */
    public record Cuadro(int rondas, List<Cruce> cruces) {

        /** Cuántos partidos tiene la ronda: la última, la final, tiene uno. */
        public int partidosDeLaRonda(int ronda) {
            return 1 << (rondas - ronda);
        }
    }

    /** 32 bytes al azar, en hexadecimal (cabe en {@code torneo.semilla_bracket}). */
    public static String nuevaSemilla() {
        byte[] bytes = new byte[32];
        AZAR.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** @param inscripciones al menos dos, sin repetidas */
    public static Cuadro sortear(String semilla, List<Long> inscripciones) {
        if (inscripciones.size() < 2) {
            throw new IllegalArgumentException("Hacen falta al menos dos inscripciones");
        }
        List<Long> orden = inscripciones.stream()
                .sorted(Comparator.comparing((Long id) -> sha256(semilla + ":" + id)).thenComparing(id -> id))
                .toList();

        int rondas = 32 - Integer.numberOfLeadingZeros(orden.size() - 1);
        int partidos = 1 << (rondas - 1);
        int byes = (1 << rondas) - orden.size();

        List<Integer> conBye = IntStream.concat(
                        IntStream.rangeClosed(1, partidos).filter(p -> p % 2 == 1),
                        IntStream.rangeClosed(1, partidos).filter(p -> p % 2 == 0))
                .limit(byes)
                .boxed()
                .toList();

        Cruce[] cruces = new Cruce[partidos];
        int siguiente = 0;
        for (int posicion : conBye) {
            cruces[posicion - 1] = new Cruce(posicion, orden.get(siguiente++), null);
        }
        for (int posicion = 1; posicion <= partidos; posicion++) {
            if (cruces[posicion - 1] == null) {
                cruces[posicion - 1] = new Cruce(posicion, orden.get(siguiente), orden.get(siguiente + 1));
                siguiente += 2;
            }
        }
        return new Cuadro(rondas, List.of(cruces));
    }

    /** Dónde sigue el ganador del partido {@code posicion}: la posición en la ronda siguiente. */
    public static int posicionSiguiente(int posicion) {
        return (posicion + 1) / 2;
    }

    /** Y de qué lado: los de posición impar entran como local. */
    public static Lado ladoSiguiente(int posicion) {
        return posicion % 2 == 1 ? Lado.LOCAL : Lado.VISITANTE;
    }

    private static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
