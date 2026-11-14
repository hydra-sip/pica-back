package com.hydra.pica.plataforma_pica.billetera.service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion.Linea;
import com.hydra.pica.plataforma_pica.billetera.dto.RepartoLiquidacion.Origen;
import com.hydra.pica.plataforma_pica.billetera.service.Acreditacion.Rol;
import com.hydra.pica.plataforma_pica.billetera.service.PanelFinancieroService.LiquidacionFinal;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * La liquidación final de un torneo para descargar (HU-608): una fila por concepto acreditado y el total, que es lo
 * que se repartió. El CSV va con punto y coma, coma decimal y BOM, para que Excel en castellano lo abra bien.
 */
public final class ArchivoLiquidacion {

    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DecimalFormatSymbols SIMBOLOS = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR"));
    private static final String[] COLUMNAS = {"Concepto", "Beneficiario", "Acreditado", "Monto"};

    private ArchivoLiquidacion() {
    }

    public static byte[] csv(LiquidacionFinal liquidacion) {
        RepartoLiquidacion reparto = liquidacion.reparto();
        StringBuilder csv = new StringBuilder("﻿");
        fila(csv, "Liquidación final", liquidacion.torneoNombre());
        fila(csv, "Liquidado el", fechaHora(reparto.liquidadoEn()));
        fila(csv, "Moneda", reparto.moneda());
        csv.append("\r\n");
        fila(csv, COLUMNAS);
        for (Fila fila : filas(liquidacion)) {
            fila(csv, fila.concepto(), fila.beneficiario(), fechaHora(fila.acreditadoEn()),
                    numero(fila.monto(), "0.00"));
        }
        fila(csv, "Total repartido", "", "", numero(reparto.total(), "0.00"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] pdf(LiquidacionFinal liquidacion) {
        RepartoLiquidacion reparto = liquidacion.reparto();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Document documento = new Document(PageSize.A4, 36, 36, 48, 48);
        PdfWriter.getInstance(documento, salida);
        documento.open();

        documento.add(new Paragraph("Liquidación final: " + liquidacion.torneoNombre(),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16)));
        documento.add(new Paragraph("Liquidado el " + fechaHora(reparto.liquidadoEn()) + " · Montos en "
                + reparto.moneda(), FontFactory.getFont(FontFactory.HELVETICA, 10)));
        documento.add(new Paragraph(" "));

        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font negrita = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        PdfPTable tabla = new PdfPTable(new float[] {4, 4, 2.2f, 1.8f});
        tabla.setWidthPercentage(100);
        tabla.setHeaderRows(1);
        for (String columna : COLUMNAS) {
            tabla.addCell(celda(columna, negrita, columna.equals("Monto")));
        }
        for (Fila fila : filas(liquidacion)) {
            tabla.addCell(celda(fila.concepto(), normal, false));
            tabla.addCell(celda(fila.beneficiario(), normal, false));
            tabla.addCell(celda(fechaHora(fila.acreditadoEn()), normal, false));
            tabla.addCell(celda(pesos(fila.monto()), normal, true));
        }
        PdfPCell total = celda("Total repartido", negrita, false);
        total.setColspan(3);
        tabla.addCell(total);
        tabla.addCell(celda(pesos(reparto.total()), negrita, true));
        documento.add(tabla);

        documento.close();
        return salida.toByteArray();
    }

    /** Una fila del archivo: un concepto, a quién, cuándo y cuánto. */
    private record Fila(String concepto, String beneficiario, Instant acreditadoEn, BigDecimal monto) {
    }

    /**
     * Una fila por concepto. Lo que cobró el organizador va en un solo movimiento: hasta su reintegro son sus gastos, y
     * lo que sobra es el pozo que nadie ganó (sin nadie en un puesto con premio, la liquidación se lo da a él).
     */
    private static List<Fila> filas(LiquidacionFinal liquidacion) {
        List<Fila> filas = new ArrayList<>();
        BigDecimal reintegroPendiente = liquidacion.reintegro();
        for (Linea linea : liquidacion.reparto().lineas()) {
            if (linea.rol() == Rol.ORGANIZADOR && linea.origen() == Origen.TORNEO) {
                BigDecimal reintegro = linea.monto().min(reintegroPendiente);
                reintegroPendiente = reintegroPendiente.subtract(reintegro);
                agregar(filas, "Reintegro de gastos al organizador", linea, reintegro);
                agregar(filas, "Pozo sin ganadores, al organizador", linea, linea.monto().subtract(reintegro));
            } else {
                agregar(filas, concepto(linea), linea, linea.monto());
            }
        }
        return filas;
    }

    private static void agregar(List<Fila> filas, String concepto, Linea linea, BigDecimal monto) {
        if (monto.signum() > 0) {
            filas.add(new Fila(concepto, beneficiario(linea), linea.acreditadoEn(), monto));
        }
    }

    /** Qué se acreditó: el premio de un puesto, los gastos administrativos, un honorario... */
    static String concepto(Linea linea) {
        String concepto = switch (linea.rol()) {
            case PREMIO -> "Premio del " + linea.puesto() + "º puesto" + (linea.origen() == Origen.RIFA ? " de la rifa" : "");
            case ORGANIZADOR -> "Pozo sin ganadores, al organizador";
            case PLATAFORMA -> "Gastos administrativos (plataforma)";
            case HONORARIO -> "Honorario arbitral";
        };
        return linea.origen() == Origen.APORTE_RIFA ? concepto + " (aporte de la rifa)" : concepto;
    }

    private static String beneficiario(Linea linea) {
        if (linea.beneficiarioUsername() == null) {
            return "Plataforma";
        }
        String quien = linea.beneficiarioNombre() + " (@" + linea.beneficiarioUsername() + ")";
        return linea.equipoNombre() == null ? quien : quien + ", " + linea.equipoNombre();
    }

    private static PdfPCell celda(String texto, Font fuente, boolean aLaDerecha) {
        PdfPCell celda = new PdfPCell(new Phrase(texto, fuente));
        celda.setPadding(4);
        celda.setHorizontalAlignment(aLaDerecha ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
        return celda;
    }

    private static void fila(StringBuilder csv, String... campos) {
        csv.append(String.join(";", List.of(campos).stream().map(ArchivoLiquidacion::campo).toList())).append("\r\n");
    }

    private static String campo(String valor) {
        return valor.contains(";") || valor.contains("\"") || valor.contains("\n")
                ? "\"" + valor.replace("\"", "\"\"") + "\"" : valor;
    }

    private static String fechaHora(Instant instante) {
        return instante == null ? "" : FECHA_HORA.format(instante.atZone(ARGENTINA));
    }

    /** $ 4.400,00 */
    private static String pesos(BigDecimal monto) {
        return "$ " + numero(monto, "#,##0.00");
    }

    private static String numero(BigDecimal monto, String patron) {
        return new DecimalFormat(patron, SIMBOLOS).format(monto);
    }
}
