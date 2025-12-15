# README - AWS Infrastructure Deployment

This README contains the information needed to deploy the infrastructure required for the project in the cloud.

## Automatic Deployment with Terraform

To use Terraform to automatically create the infrastructure in the cloud, run:

```bash
cd infra
```

* Open `main.tf` and modify `bucket = "tf-ilies411-iot-traffic-input"` to another bucket name if desired.

* Open summarize-worker/src/main/java/com/aws/iot/SummarizeWorker.java and modify the queue URL to `https://sqs.us-east-1.amazonaws.com/<aws-account-id>/iot-queue-2`.

* Build the summarize worker:

```bash
cd summarize-worker
mvn clean package
```

* Open `consolidator-worker/src/main/java/com/aws/iot/ConsolidatorWorker.java` and modify the bucket name to the one you chose in `main.tf`.

* Build the consolidator worker:

```bash
cd consolidator-worker
mvn clean package
```
* Open `main.tf` and change lambda filenames to `summarize-worker/target/summarize-worker-1.0-SNAPSHOT-jar-with-dependencies.jar` and `consolidator-worker/target/consolid-worker-1.0-SNAPSHOT-jar-with-dependencies.jar` respectively

* Deploy the infrastructure:

```bash
cd infra
terraform init
terraform apply
```
Your infrastructure is now ready. You can follow the [main README](../README.md) instructions to run the pipeline.

## Manual Deployment

### S3 Bucket

* Create a bucket on AWS with a name of your choice.

* Open `consolidator-worker/src/main/java/com/aws/iot/ConsolidatorWorker.java` and modify the bucket name to the one you created.

* Build the consolidator worker:

```bash
cd consolidator-worker
mvn clean package
```

### SQS Queues

* Create a standard queue `iot-queue-1` and another standard queue `iot-queue-2`.

* Open `summarize-worker/src/main/java/com/aws/iot/SummarizeWorker.java` and modify the queue URL to `https://sqs.us-east-1.amazonaws.com/<aws-account-id>/iot-queue-2`.

* Build the summarize worker:

```bash
cd summarize-worker
mvn clean package
```

### Lambdas 

#### Summarize Worker

* Create a Lambda function `summarize-worker` with Java 17 runtime and IAM role Labrole.

* Add the SQS queue `iot-queue-1` as a trigger.

* Upload the JAR `summarize-worker/target/summarize-worker-1.0-SNAPSHOT-jar-with-dependencies.jar`.


#### Consolidator Worker

* Create a Lambda function `consolidator-worker` with Java 17 runtime and IAM role Labrole.

* Add the SQS queue `iot-queue-2 as a trigger`.

* Upload the JAR `consolidator-worker/target/consolid-worker-1.0-SNAPSHOT-jar-with-dependencies.jar`.

Your infrastructure is now ready. You can follow the [main README](../README.md) instructions to run the pipeline.
