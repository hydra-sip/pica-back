package com.hydra.pica.plataforma_pica.partido.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * La tabla de posiciones de un grupo (CR-02, PICA-399), sin base de datos. Solo cuentan los partidos terminados.
 *
 * <p>Puntos: los de {@code ReglasGrupos} por ganar y por empatar; perder da 0. Un walkover suma la victoria sin tantos;
 * si perdieron los dos (sin presentes), ninguno suma.
 *
 * <p>Desempate, en cascada y sin estadísticas que dependan del deporte:
 * <ol>
 * <li>puntos;</li>
 * <li>resultado entre sí: los puntos de los partidos entre los empatados; si separa a algunos, se vuelve a empezar
 * entre los que siguen iguales;</li>
 * <li>partidos ganados (en el grupo); igual, si separa a algunos se vuelve a empezar entre los que siguen;</li>
 * <li>partido de desempate: el orden que dejó el mini cuadro de los empatados, si ya se jugó.</li>
 * </ol>
 * Lo que sigue empatado comparte el puesto (criterio {@link Criterio#EMPATE}). Con el grupo terminado, un empate así
 * que toca un puesto que clasifica queda en {@link Tabla#desempatesPendientes}: hay que jugarlo.
 */
public final class TablaGrupo {

    private TablaGrupo() {
    }

    /** Con qué se definió el puesto. */
    public enum Criterio {
        PUNTOS,
        ENTRE_SI,
        GANADOS,
        DESEMPATE,
        /** Sigue empatado: comparte el puesto con los demás del bloque. */
        EMPATE
    }

    /**
     * Un partido terminado. {@code tantos*} en null si fue walkover; {@code gana*} los dos en false si fue empate o si
     * perdieron los dos.
     */
    public record Resultado(long local, long visitante, Integer tantosLocal, Integer tantosVisitante,
                            boolean ganaLocal, boolean ganaVisitante) {

        boolean empate() {
            return !ganaLocal && !ganaVisitante && tantosLocal != null && tantosLocal.equals(tantosVisitante);
        }

        boolean juega(long id) {
            return local == id || visitante == id;
        }
    }

    /**
     * @param posicion  1 el primero; los que comparten puesto tienen el mismo
     * @param clasifica si pasa a la llave: su puesto (y el de todo su bloque, si comparte) entra en los que pasan
     */
    public record Fila(long inscripcionId, int posicion, int jugados, int ganados, int empatados, int perdidos,
                       int aFavor, int enContra, int puntos, Criterio criterio, boolean clasifica) {

        public int diferencia() {
            return aFavor - enContra;
        }
    }

    /**
     * @param desempatesPendientes los bloques empatados que tocan un puesto que clasifica y hay que definir con un
     *                             partido; solo con el grupo terminado, de arriba hacia abajo
     */
    public record Tabla(List<Fila> filas, List<List<Long>> desempatesPendientes) {
    }

    /**
     * @param integrantes los del grupo
     * @param resultados  los partidos terminados del grupo
     * @param terminado   si ya no queda ninguno por jugar: recién ahí hay desempates pendientes
     * @param desempates  el orden que dejó cada partido de desempate ya jugado, por el conjunto de empatados
     */
    public static Tabla calcular(List<Long> integrantes, List<Resultado> resultados, int puntosVictoria,
                                 int puntosEmpate, int clasifican, boolean terminado,
                                 Map<Set<Long>, List<Long>> desempates) {
        Calculo calculo = new Calculo(resultados, puntosVictoria, puntosEmpate, desempates);
        List<Long> todos = integrantes.stream().sorted().toList();
        List<List<Long>> bloques = new ArrayList<>();
        for (List<Long> porPuntos : calculo.partir(todos, calculo::puntos)) {
            if (porPuntos.size() == 1) {
                calculo.criterio.put(porPuntos.getFirst(), Criterio.PUNTOS);
                bloques.add(porPuntos);
            } else {
                bloques.addAll(calculo.resolver(porPuntos));
            }
        }

        List<Fila> filas = new ArrayList<>();
        List<List<Long>> pendientes = new ArrayList<>();
        int posicion = 1;
        for (List<Long> bloque : bloques) {
            boolean clasifica = posicion + bloque.size() - 1 <= clasifican;
            if (terminado && bloque.size() > 1 && posicion <= clasifican) {
                pendientes.add(bloque);
            }
            for (Long id : bloque) {
                filas.add(calculo.fila(id, posicion, clasifica));
            }
            posicion += bloque.size();
        }
        return new Tabla(List.copyOf(filas), List.copyOf(pendientes));
    }

    private static final class Calculo {

        private final List<Resultado> resultados;
        private final int victoria;
        private final int empate;
        private final Map<Set<Long>, List<Long>> desempates;
        private final Map<Long, Criterio> criterio = new HashMap<>();

        Calculo(List<Resultado> resultados, int victoria, int empate, Map<Set<Long>, List<Long>> desempates) {
            this.resultados = resultados;
            this.victoria = victoria;
            this.empate = empate;
            this.desempates = desempates;
        }

        /** Un bloque empatado en puntos: los criterios en orden; lo que no se separa queda junto. */
        List<List<Long>> resolver(List<Long> bloque) {
            Set<Long> entre = new HashSet<>(bloque);
            List<List<Long>> porEntreSi = partir(bloque, id -> puntos(id, entre));
            if (porEntreSi.size() > 1) {
                return seguir(porEntreSi, Criterio.ENTRE_SI);
            }
            List<List<Long>> porGanados = partir(bloque, this::ganados);
            if (porGanados.size() > 1) {
                return seguir(porGanados, Criterio.GANADOS);
            }
            List<Long> desempate = desempates.get(Set.copyOf(bloque));
            if (desempate != null) {
                desempate.forEach(id -> criterio.put(id, Criterio.DESEMPATE));
                return desempate.stream().map(List::of).toList();
            }
            bloque.forEach(id -> criterio.put(id, Criterio.EMPATE));
            return List.of(bloque);
        }

        /** Los que quedaron solos se definieron por {@code porque}; los que siguen juntos, de nuevo desde el principio. */
        private List<List<Long>> seguir(List<List<Long>> partes, Criterio porque) {
            List<List<Long>> bloques = new ArrayList<>();
            for (List<Long> parte : partes) {
                if (parte.size() == 1) {
                    criterio.put(parte.getFirst(), porque);
                    bloques.add(parte);
                } else {
                    bloques.addAll(resolver(parte));
                }
            }
            return bloques;
        }

        /** Agrupados por valor, de mayor a menor; dentro de cada grupo, por id. */
        List<List<Long>> partir(List<Long> ids, ToIntFunction<Long> valor) {
            Map<Integer, List<Long>> porValor = new LinkedHashMap<>();
            ids.stream()
                    .sorted(Comparator.comparingInt((Long id) -> valor.applyAsInt(id)).reversed()
                            .thenComparing(id -> id))
                    .forEach(id -> porValor.computeIfAbsent(valor.applyAsInt(id), v -> new ArrayList<>()).add(id));
            return List.copyOf(porValor.values());
        }

        int puntos(long id) {
            return puntos(id, null);
        }

        /** Con {@code entre}, solo los partidos contra los de ese conjunto. */
        int puntos(long id, Set<Long> entre) {
            int puntos = 0;
            for (Resultado r : de(id, entre)) {
                if (r.empate()) {
                    puntos += empate;
                } else if (r.local() == id ? r.ganaLocal() : r.ganaVisitante()) {
                    puntos += victoria;
                }
            }
            return puntos;
        }

        int ganados(long id) {
            return (int) de(id, null).stream().filter(r -> r.local() == id ? r.ganaLocal() : r.ganaVisitante()).count();
        }

        private List<Resultado> de(long id, Set<Long> entre) {
            return resultados.stream()
                    .filter(r -> r.juega(id))
                    .filter(r -> entre == null || (entre.contains(r.local()) && entre.contains(r.visitante())))
                    .toList();
        }

        Fila fila(long id, int posicion, boolean clasifica) {
            List<Resultado> suyos = de(id, null);
            int ganados = ganados(id);
            int empatados = (int) suyos.stream().filter(Resultado::empate).count();
            int aFavor = 0;
            int enContra = 0;
            for (Resultado r : suyos) {
                Integer propios = r.local() == id ? r.tantosLocal() : r.tantosVisitante();
                Integer ajenos = r.local() == id ? r.tantosVisitante() : r.tantosLocal();
                aFavor += propios == null ? 0 : propios;
                enContra += ajenos == null ? 0 : ajenos;
            }
            return new Fila(id, posicion, suyos.size(), ganados, empatados, suyos.size() - ganados - empatados, aFavor,
                    enContra, puntos(id), criterio.get(id), clasifica);
        }
    }
}
