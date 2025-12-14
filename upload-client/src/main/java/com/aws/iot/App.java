package com.aws.iot;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.File;

public class App {
    public static void main(String[] args) {

        String bucket = "iot-traffic-input";  
        String folderPath = "iot-data";       

        // URL de ta queue
        String queueUrl = "https://sqs.us-east-1.amazonaws.com/772595554692/iot-queue1";

        File folder = new File(folderPath);

        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("❌ Le dossier " + folderPath + " n'existe pas.");
            return;
        }

        S3Client s3 = S3Client.builder().build();
        SqsClient sqs = SqsClient.builder().build();

        for (File file : folder.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".csv")) {

                String key = file.getName();

                // Vérifier si le fichier existe déjà dans S3
                try {
                    s3.headObject(HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build());

                    System.out.println("⏭ Fichier déjà présent dans S3 → " + key + " (ignoré)");
                    continue;

                } catch (Exception ignored) {
                    System.out.println("⬆ Nouveau fichier détecté : " + key);
                }

                // Upload du fichier
                s3.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build(),
                        file.toPath()
                );

                System.out.println("✅ Upload terminé : " + key);

                // Envoyer un message SQS au Summarize Worker
                String jsonMessage = "{ \"bucket\": \"" + bucket + "\", \"key\": \"" + key + "\" }";

                SendMessageRequest sqsRequest = SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(jsonMessage)
                        .build();

                sqs.sendMessage(sqsRequest);

                System.out.println("📨 Message SQS envoyé pour : " + key);
            }
        }

        System.out.println("\n🎉 Tous les nouveaux fichiers ont été uploadés et envoyés à SQS !");
    }
}
