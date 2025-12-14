package com.aws.iot;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import org.apache.commons.csv.*;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ConsolidatorWorker implements RequestHandler<SQSEvent, Void> {

    // ------------------------------
    // Configuration S3
    // ------------------------------
    private static final String BUCKET = "ilies411-iot-traffic-input";
    private static final String CONSOLIDATED_KEY = "consolidated/traffic_stats.csv";

    private final S3Client s3 = S3Client.builder().build();

    // -------------------------------------------------------------
    // Structure interne : stats incrémentales (algorithme Welford)
    // -------------------------------------------------------------
    static class Stats {
        long count = 0;
        double meanFD = 0; // Flow Duration
        double m2FD = 0;
        double meanFP = 0; // Forward Packets
        double m2FP = 0;
    }

    // -----------------------------------------------------------------
    // LAMBDA HANDLER
    // -----------------------------------------------------------------
    @Override
    public Void handleRequest(SQSEvent event, Context context) {

        for (SQSEvent.SQSMessage msg : event.getRecords()) {

            context.getLogger().log(" Message reçu : " + msg.getBody());

            // Message JSON : { "summary": "summary_xxxxx.csv" }
            String summaryName = extractSummaryName(msg.getBody());
            String summaryKey = "summaries/" + summaryName;

            try {
                context.getLogger().log(" Téléchargement du résumé depuis S3 : " + summaryKey);
                Path summaryFile = downloadSummaryFromS3(summaryKey, context);
                context.getLogger().log(" Résumé téléchargé dans : " + summaryFile);

                context.getLogger().log(" Chargement du fichier consolidé (si existe)");
                Map<String, Stats> globalStats = loadExistingConsolidated();

                context.getLogger().log(" Intégration des données : " + summaryName);
                integrateSummary(summaryFile, globalStats);

                context.getLogger().log(" Écriture du fichier consolidé mis à jour");
                writeConsolidated(globalStats);

                Files.deleteIfExists(summaryFile);

                context.getLogger().log(" Consolidation terminée pour : " + summaryName);

            } catch (Exception e) {
                context.getLogger().log(" ERREUR ConsolidatorWorker : " + e.getMessage());
            }
        }

        return null;
    }

    // ------------------------------------------------------------------------
    // Extraction JSON : { "summary": "xxx.csv" }
    // ------------------------------------------------------------------------
    private String extractSummaryName(String json) {
        json = json.replace("{", "").replace("}", "").replace("\"", "").trim();
        return json.split(":")[1].trim();
    }

    // ------------------------------------------------------------------------
    // Téléchargement S3 CORRIGÉ (compatible Lambda !)
    // ------------------------------------------------------------------------
    private Path downloadSummaryFromS3(String key, Context context) throws IOException {

        Path tmp = Files.createTempFile("summary_", ".csv");
        context.getLogger().log("Fichier Temp créé: " + tmp.toString());

        byte[] data = s3.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .build())
                .asByteArray();

        Files.write(tmp, data);

        context.getLogger().log("Résumé téléchargé depuis S3");
        return tmp;
    }

    // ------------------------------------------------------------------------
    // Chargement du fichier consolidé s'il existe
    // ------------------------------------------------------------------------
    private Map<String, Stats> loadExistingConsolidated() {

        Map<String, Stats> map = new HashMap<>();

        try {
            Path tmp = Files.createTempFile("conso_", ".csv");

            byte[] data = s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(CONSOLIDATED_KEY)
                            .build())
                    .asByteArray();

            Files.write(tmp, data);

            Reader reader = Files.newBufferedReader(tmp);

            CSVParser parser = CSVParser.parse(
                    reader,

                    CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .build());

            for (CSVRecord r : parser) {

                String src = r.get("SrcIP");
                String dst = r.get("DstIP");
                String key = src + "_" + dst;

                Stats s = new Stats();
                s.count = Long.parseLong(r.get("Count"));

                s.meanFD = Double.parseDouble(r.get("AvgFlowDuration"));
                s.m2FD = Math.pow(Double.parseDouble(r.get("StdFlowDuration")), 2) * Math.max(1, s.count - 1);

                s.meanFP = Double.parseDouble(r.get("AvgFwdPkts"));
                s.m2FP = Math.pow(Double.parseDouble(r.get("StdFwdPkts")), 2) * Math.max(1, s.count - 1);

                map.put(key, s);
            }

        } catch (Exception ex) {
            // NORMAL : c’est le premier fichier → aucun consolidé existant
        }

        return map;
    }

    // ------------------------------------------------------------------------
    // Mise à jour des statistiques (Welford)
    // ------------------------------------------------------------------------
    private void integrateSummary(Path summaryFile, Map<String, Stats> map)
            throws IOException {

        Reader reader = Files.newBufferedReader(summaryFile);
        CSVParser parser = CSVParser.parse(
                reader,
                CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build());

        for (CSVRecord r : parser) {

            String src = r.get("SrcIP");
            String dst = r.get("DstIP");

            long FD = Long.parseLong(r.get("TotalFlowDuration"));
            long FP = Long.parseLong(r.get("TotalFwdPkts"));

            String key = src + "_" + dst;

            Stats s = map.getOrDefault(key, new Stats());

            // Nombre de jours
            s.count++;

            double deltaFD = FD - s.meanFD;
            s.meanFD += deltaFD / s.count;
            s.m2FD += deltaFD * (FD - s.meanFD);

            // ForwardPackets
            double deltaFP = FP - s.meanFP;
            s.meanFP += deltaFP / s.count;
            s.m2FP += deltaFP * (FP - s.meanFP);

            map.put(key, s);
        }

    }

    // ------------------------------------------------------------------------
    // Écriture du fichier consolidé final dans S3
    // ------------------------------------------------------------------------
    private void writeConsolidated(Map<String, Stats> map) throws IOException {

        Path tmp = Files.createTempFile("conso_write_", ".csv");
        BufferedWriter writer = Files.newBufferedWriter(tmp);

        writer.write("SrcIP,DstIP,Count,AvgFlowDuration,StdFlowDuration,AvgFwdPkts,StdFwdPkts\n");

        for (String key : map.keySet()) {

            String[] parts = key.split("_");
            String src = parts[0];
            String dst = parts[1];

            Stats s = map.get(key);

            double stdFD = (s.count > 1)
                    ? Math.sqrt(s.m2FD / (s.count - 1))
                    : 0;

            double stdFP = (s.count > 1)
                    ? Math.sqrt(s.m2FP / (s.count - 1))
                    : 0;

            writer.write(src + "," + dst + "," +
                    s.count + "," +
                    s.meanFD + "," + stdFD + "," +
                    s.meanFP + "," + stdFP + "\n");
        }

        writer.close();

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(CONSOLIDATED_KEY)
                        .build(),
                tmp);
    }
}
