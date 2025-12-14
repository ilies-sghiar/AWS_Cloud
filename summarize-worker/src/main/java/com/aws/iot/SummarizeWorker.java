package com.aws.iot;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.*;

public class SummarizeWorker implements RequestHandler<SQSEvent, Void> {

    private final S3Client s3 = S3Client.builder().build();
    private final SqsClient sqs = SqsClient.builder().build();

    // URL DE LA QUEUE DU CONSOLIDATOR
    private static final String CONSOLIDATOR_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/196036494548/iot-queue-2";

    @Override
    public Void handleRequest(SQSEvent event, Context context) {

        for (SQSEvent.SQSMessage msg : event.getRecords()) {

            context.getLogger().log(" Message reçu: " + msg.getBody());

            // Le message SQS contient un JSON simple : {"bucket": "...", "key": "..."}
            Map<String, String> info = parseJson(msg.getBody());
            String bucket = info.get("bucket");
            String key = info.get("key");

            context.getLogger().log("️ Traitement du fichier: " + key + " du bucket: " + bucket);

            try {
                // Étape 1 — Télécharger le CSV depuis S3
                Path tempFile = downloadFromS3(bucket, key, context);

                context.getLogger().log("Fichier téléchargé");

                // Étape 2 — Résumer le CSV
                Map<String, Summary> summary = summarizeCsv(tempFile);

                // Étape 3 — Générer un fichier résumé
                Path summaryFile = generateSummaryFile(key, summary);

                // Étape 4 — Upload du résumé dans S3
                uploadSummaryToS3(bucket, summaryFile, key);

                // Étape 5 — Supprimer l'ancien fichier
                deleteOriginal(bucket, key);

                // Étape 6 — Notifier le Consolidator Worker
                sendToConsolidator(key);

                // Supprimer les fichiers locaux temporaires
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(summaryFile);

            } catch (Exception e) {
                context.getLogger().log(" ERREUR : " + e.getMessage());
            }
        }

        return null;
    }

    // Convertir JSON simple {"bucket": "...", "key": "..."} → Map
    private Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        json = json.trim().replace("{", "").replace("}", "").replace("\"", "");
        for (String part : json.split(",")) {
            String[] kv = part.split(":");
            map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    // Télécharger depuis S3
    private Path downloadFromS3(String bucket, String key, Context context) throws IOException {
        Path tempFile = Files.createTempFile("iot_", ".csv");
        context.getLogger().log("Fichier Temp créé: " + tempFile.toString());

        ResponseInputStream<?> s3Object = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());

        Files.copy(s3Object, tempFile, StandardCopyOption.REPLACE_EXISTING);
        s3Object.close();

        context.getLogger().log("Fichier téléchargé depuis S3");
        return tempFile;
    }

    // Structure des totaux calculés
    static class Summary {
        long totalFlowDuration = 0;
        long totalFwdPkts = 0;
    }

    // Résumer le CSV (SrcIP, DstIP, Date)
    private Map<String, Summary> summarizeCsv(Path csvFile) throws IOException {

        Map<String, Summary> map = new HashMap<>();

        Reader reader = Files.newBufferedReader(csvFile);

        CSVParser parser = CSVParser.parse(
                reader,
                CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build());

        for (CSVRecord record : parser) {

            String src = record.get("Src IP");
            String dst = record.get("Dst IP");

            long flowDuration = Long.parseLong(record.get("Flow Duration"));
            long totFwdPkts = Long.parseLong(record.get("Tot Fwd Pkts"));

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a");

            String timestampStr = record.get("Timestamp");
            LocalDateTime dateTime = LocalDateTime.parse(timestampStr, formatter);
            String date = dateTime.toLocalDate().toString();

            String key = date + "_" + src + "_" + dst;

            Summary s = map.getOrDefault(key, new Summary());
            s.totalFlowDuration += flowDuration;
            s.totalFwdPkts += totFwdPkts;
            map.put(key, s);
        }

        return map;
    }

    // Générer le fichier CSV résumé
    private Path generateSummaryFile(String originalKey, Map<String, Summary> summary) throws IOException {

        // String summaryName = originalKey.replace(".csv", "") + "_summary.csv";
        Path summaryFile = Files.createTempFile("summary_", ".csv");

        BufferedWriter writer = Files.newBufferedWriter(summaryFile);
        writer.write("Date,SrcIP,DstIP,TotalFlowDuration,TotalFwdPkts\n");

        for (String k : summary.keySet()) {
            Summary s = summary.get(k);
            String[] parts = k.split("_");
            String date = parts[0];
            String src = parts[1];
            String dst = parts[2];

            writer.write(date + "," + src + "," + dst + "," +
                    s.totalFlowDuration + "," + s.totalFwdPkts + "\n");
        }

        writer.close();
        return summaryFile;
    }

    // Upload du résumé
    private void uploadSummaryToS3(String bucket, Path summaryFile, String originalKey) {

        String key = "summaries/" + originalKey.replace(".csv", "_summary.csv");

        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                summaryFile);
    }

    // Supprimer le fichier CSV original
    private void deleteOriginal(String bucket, String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    // Envoi d'un message à consolidator-queue
    private void sendToConsolidator(String originalKey) {

        String json = "{ \"summary\": \"" +
                originalKey.replace(".csv", "_summary.csv") + "\" }";

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(CONSOLIDATOR_QUEUE_URL)
                .messageBody(json)
                .build());
    }
}
