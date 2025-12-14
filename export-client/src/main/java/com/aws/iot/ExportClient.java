package com.aws.iot;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.regions.Region;

import org.apache.commons.csv.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ExportClient {

    // ---------------- CONFIG ----------------
    private static final String BUCKET = "ilies411-iot-traffic-input";
    private static final String SUMMARIES_PREFIX = "summaries/";
    private static final String CONSOLIDATED_KEY = "consolidated/traffic_stats.csv";

    // ----------------------------------------
    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            System.out.println("Usage: java -jar export-client.jar <SrcIP> <DstIP>");
            return;
        }

        String srcIP = args[0];
        String dstIP = args[1];

        S3Client s3 = S3Client.builder()
                .region(Region.US_EAST_1)
                .build();

        // Charger les statistiques consolidées
        ConsolidatedStats stats = loadConsolidatedStats(s3, srcIP, dstIP);
        if (stats == null) {
            System.out.println("Couple non trouvé dans traffic_stats.csv");
            return;
        }

        // Lire tous les summaries et filtrer le couple
        List<DailyRecord> dailyRecords = readDailySummaries(s3, srcIP, dstIP);

        if (dailyRecords.isEmpty()) {
            System.out.println(" Aucun résumé journalier trouvé pour ce couple");
            return;
        }

        // Écriture du CSV final local
        writeFinalCSV(srcIP, dstIP, dailyRecords, stats);

        System.out.println("Export terminé avec succès !");

        s3.close();
    }

    // --------------------------------------------------------
    // STRUCTURES INTERNES
    // --------------------------------------------------------

    static class DailyRecord {
        String date;
        long totalFlowDuration;
        long totalFwdPkts;
    }

    static class ConsolidatedStats {
        long count;
        double avgFD;
        double stdFD;
        double avgFP;
        double stdFP;
    }

    // --------------------------------------------------------
    // LECTURE DU FICHIER CONSOLIDÉ
    // --------------------------------------------------------
    private static ConsolidatedStats loadConsolidatedStats(
            S3Client s3, String srcIP, String dstIP) throws IOException {

        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(BUCKET)
                .key(CONSOLIDATED_KEY)
                .build();

        try (InputStream is = s3.getObject(req);
                Reader reader = new BufferedReader(new InputStreamReader(is));
                CSVParser parser = CSVParser.parse(
                        reader,
                        CSVFormat.DEFAULT.builder()
                                .setHeader()
                                .setSkipHeaderRecord(true)
                                .build());) {

            for (CSVRecord r : parser) {
                if (r.get("SrcIP").equals(srcIP) && r.get("DstIP").equals(dstIP)) {
                    ConsolidatedStats s = new ConsolidatedStats();
                    s.count = Long.parseLong(r.get("Count"));
                    s.avgFD = Double.parseDouble(r.get("AvgFlowDuration"));
                    s.stdFD = Double.parseDouble(r.get("StdFlowDuration"));
                    s.avgFP = Double.parseDouble(r.get("AvgFwdPkts"));
                    s.stdFP = Double.parseDouble(r.get("StdFwdPkts"));
                    return s;
                }
            }
        }
        return null;
    }

    // --------------------------------------------------------
    // LECTURE DES FICHIERS SUMMARY
    // --------------------------------------------------------
    private static List<DailyRecord> readDailySummaries(
            S3Client s3, String srcIP, String dstIP) throws IOException {

        List<DailyRecord> results = new ArrayList<>();

        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(BUCKET)
                .prefix(SUMMARIES_PREFIX)
                .build();

        ListObjectsV2Response listRes = s3.listObjectsV2(listReq);

        for (S3Object obj : listRes.contents()) {

            GetObjectRequest getReq = GetObjectRequest.builder()
                    .bucket(BUCKET)
                    .key(obj.key())
                    .build();

            try (InputStream is = s3.getObject(getReq);
                    Reader reader = new BufferedReader(new InputStreamReader(is));
                    CSVParser parser = CSVParser.parse(
                            reader,
                            CSVFormat.DEFAULT.builder()
                                    .setHeader()
                                    .setSkipHeaderRecord(true)
                                    .build())) {

                for (CSVRecord r : parser) {
                    if (r.get("SrcIP").equals(srcIP) && r.get("DstIP").equals(dstIP)) {

                        DailyRecord d = new DailyRecord();
                        d.date = r.get("Date");
                        d.totalFlowDuration = Long.parseLong(r.get("TotalFlowDuration"));
                        d.totalFwdPkts = Long.parseLong(r.get("TotalFwdPkts"));
                        results.add(d);
                    }
                }
            }

        }
        return results;
    }

    // --------------------------------------------------------
    // ÉCRITURE DU CSV FINAL (LOCAL)
    // --------------------------------------------------------
    private static void writeFinalCSV(
            String srcIP, String dstIP,
            List<DailyRecord> daily,
            ConsolidatedStats stats) throws IOException {

        String fileName = "export_" + srcIP + "_" + dstIP + ".csv";
        Path output = Paths.get(fileName);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader("Date", "SrcIP", "DstIP",
                        "TotalFlowDuration", "TotalFwdPkts",
                        "Count",
                        "AvgFlowDuration", "StdFlowDuration",
                        "AvgFwdPkts", "StdFwdPkts")
                .build();

        try (BufferedWriter writer = Files.newBufferedWriter(output);

                CSVPrinter printer = new CSVPrinter(writer, format)) {

            for (DailyRecord d : daily) {
                printer.printRecord(
                        d.date,
                        srcIP,
                        dstIP,
                        d.totalFlowDuration,
                        d.totalFwdPkts,
                        stats.count,
                        stats.avgFD,
                        stats.stdFD,
                        stats.avgFP,
                        stats.stdFP);
            }
        }

        System.out.println("Fichier exporté localement : " + fileName);

    }
}
