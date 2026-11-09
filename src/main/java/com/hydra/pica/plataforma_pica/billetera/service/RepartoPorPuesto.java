package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reparte un pozo entre los que terminaron en cada puesto, con los porcentajes sellados al publicar (HU-606).
 * <ul>
 * <li>Los empatados en un puesto (los dos que pierden en semifinales quedan 3.º) ocupan ese puesto y los siguientes:
 * juntan sus porcentajes y los dividen en partes iguales.</li>
 * <li>Lo de un puesto que nadie ocupa (un premio al 3.º con dos participantes) se reparte entre los que cobran, en
 * proporción a su porcentaje: el pozo se reparte entero.</li>
 * <li>Cada monto se redondea hacia abajo al centavo y los centavos que sobran van al mejor puesto, así lo repartido
 * suma exactamente el pozo.</li>
 * </ul>
 * Sin nadie que cobre (no hay premios o nadie terminó en un puesto con premio) devuelve un reparto vacío.
 */
public final class RepartoPorPuesto {

    private RepartoPorPuesto() {
    }

    /** Dónde terminó una inscripción. */
    public record Posicionado(Long inscripcionId, int posicion) {
    }

    /** Lo que cobra una inscripción y desde qué puesto. */
    public record Cobro(Long inscripcionId, int puesto, BigDecimal monto) {
    }

    /**
     * @param pozo         lo que se reparte, en pesos con dos decimales
     * @param pctPorPuesto el porcentaje de cada puesto con premio
     * @param posicionados los que terminaron en algún puesto
     * @return un cobro por inscripción que cobra, del mejor puesto al peor; los montos suman {@code pozo}
     */
    public static List<Cobro> repartir(BigDecimal pozo, Map<Integer, BigDecimal> pctPorPuesto,
                                       List<Posicionado> posicionados) {
        if (pozo.signum() <= 0) {
            return List.of();
        }
        Map<Integer, List<Long>> porPuesto = new TreeMap<>();
        posicionados.stream()
                .sorted(Comparator.comparingInt(Posicionado::posicion).thenComparing(Posicionado::inscripcionId))
                .forEach(p -> porPuesto.computeIfAbsent(p.posicion(), k -> new ArrayList<>()).add(p.inscripcionId()));

        Map<Integer, BigDecimal> pctDelGrupo = new LinkedHashMap<>();
        BigDecimal pctQueCobra = BigDecimal.ZERO;
        for (Map.Entry<Integer, List<Long>> grupo : porPuesto.entrySet()) {
            BigDecimal pct = BigDecimal.ZERO;
            for (int puesto = grupo.getKey(); puesto < grupo.getKey() + grupo.getValue().size(); puesto++) {
                pct = pct.add(pctPorPuesto.getOrDefault(puesto, BigDecimal.ZERO));
            }
            if (pct.signum() > 0) {
                pctDelGrupo.put(grupo.getKey(), pct);
                pctQueCobra = pctQueCobra.add(pct);
            }
        }
        if (pctQueCobra.signum() == 0) {
            return List.of();
        }

        List<Cobro> cobros = new ArrayList<>();
        BigDecimal repartido = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> grupo : pctDelGrupo.entrySet()) {
            List<Long> inscripciones = porPuesto.get(grupo.getKey());
            BigDecimal cadaUno = pozo.multiply(grupo.getValue())
                    .divide(pctQueCobra.multiply(BigDecimal.valueOf(inscripciones.size())), 2, RoundingMode.DOWN);
            for (Long inscripcionId : inscripciones) {
                cobros.add(new Cobro(inscripcionId, grupo.getKey(), cadaUno));
                repartido = repartido.add(cadaUno);
            }
        }
        // los centavos del redondeo, al mejor puesto
        Cobro primero = cobros.get(0);
        cobros.set(0, new Cobro(primero.inscripcionId(), primero.puesto(), primero.monto().add(pozo.subtract(repartido))));
        return cobros.stream().filter(c -> c.monto().signum() > 0).toList();
    }
}
