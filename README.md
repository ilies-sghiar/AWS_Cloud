# IoT Traffic Data Processing using AWS

This project focuses on processing and summarizing IoT traffic data collected from geographically distributed devices in a multinational enterprise. The solution uses AWS Cloud Services to process and manage the data efficiently, reducing the need for a permanent infrastructure.

## Overview of the problem

A multi-national enterprise manages IoT network traffic data. Each branch of this enterprise produces CSV files containing multiple columns and many rows of raw traffic data sent by IoT devices, such as SrcIP, DstIP, flow duration, and forwarded packets.

The goal is to transform these raw files into useful information by computing daily summarized statistics and global consolidated statistics for each pair of devices.

Finally, the system produces a CSV file on demand that combines daily results and consolidated metrics for a given (SrcIP, DstIP) pair.

## Solution

The solution is composed of several parts. Each part has a clear role in data processing.

### Upload Client:
The Upload Client runs on the local computer. It sends the CSV files to an S3 bucket and informs the system that a new file is available.

### Summarize Worker:
The Summarize Worker is a Lambda function. It reads each CSV file and creates daily summaries for each (SrcIP, DstIP) pair.

### Consolidator Worker:
The Consolidator Worker is also a Lambda function. It combines the daily summaries and calculates the average and standard deviation.

### Export Client:
The Export Client is executed locally using the command line. It collects the summarized data and the global statistics and writes them into a CSV file.

The different parts work together using AWS services. S3 is used to store the files, SQS is used to send messages, and Lambda is used to process the data.

## Prerequisites

To run this project, you need the following prerequisites:

* Java 17 installed on your local machine.

* Apache Maven > 3.8: used for building and packaging the Java application.

* AWS SDK for Java 2.x: for interacting with AWS services like S3, SQS, and SNS.

* AWS Account: set up with access to the required AWS services (Lambda, EC2, S3, SQS, SNS).

Important: the underlying infrastructure of the project must be deployed in AWS. You can either create it manually or automatically using Terraform. See [infra/README.md](infra/README.md) for more information.

## Run the project

To upload the csvs in the upload-client/iot-data folder in S3 so they will be automatically processed:

* Open the file `upload-client/src/main/java/com/aws/iot/App.java` and modify the S3 bucket name to the one you chose in AWS and set the SQS queue URL to `https://sqs.us-east-1.amazonaws.com/<aws-account-id>/iot-queue-1` (see [infra/README.md](infra/README.md)).

* Run: 

```bash
cd upload-client
mvn clean package
mvn exec:java -Dexec.mainClass="com.aws.iot.App"
```

To export the final csv file for a couple of device (SrcIP, DstIP) in your local storage:

* Open the file `export-client/src/main/java/com/aws/iot/ExportClient.java` and modify the S3 bucket name to the one you chose in AWS (see [infra/README.md](infra/README.md)).

* Run:

```bash

cd export-client/
mvn clean package
mvn exec:java -Dexec.mainClass="com.aws.iot.ExportClient" -Dexec.args="SrcIp DstIP"
```

## Workflow of components

### Upload Client

Function: Uploads raw IoT traffic data in CSV format to Amazon S3.

Implementation: A Java application executed locally from the command line.

Workflow:
Reads CSV files stored in a local folder, uploads each CSV file to the S3 bucket, and sends a notification message to an Amazon SQS queue to trigger the Summarize Worker.


### Summarize Worker

Function: Processes CSV files by calculating daily statistics for each (SrcIP, DstIP) pair: 
* Total Flow Duration (sum of Flow Duration per day).
* Total Forward Packets (sum of Tot Fwd Pkts per day).

Implementation: As an AWS Lambda function.

Workflow:
Listens for messages from the SQS queue after a file upload, downloads the CSV file from S3 and parses its content, computes daily summarized statistics per (SrcIP, DstIP), stores the summarized results as a CSV file in the `summaries/` folder in S3, sends a message to another SQS queue to notify the Consolidator Worker, and deletes the original CSV files to reduce cloud storage usage.

### Consolidator Worker

Function: Consolidates global statistics by updating the average and standard deviation for each (SrcIP, DstIP) pair.

Implementation: As an AWS Lambda function.

Workflow:
Receives messages from the SQS queue linked to the Summarize Worker, downloads the corresponding summary CSV file from S3, loads the existing consolidated file `traffic_stats.csv` if it exists, updates statistics for existing pairs or initializes new ones, and writes the updated `traffic_stats`.csv back to the `consolidated/` folder in S3.

### Export Client

Function: Exports both daily summarized data and consolidated statistics for a given (SrcIP, DstIP) pair into a CSV file.

Implementation: A Java application executed locally from the command line (e.g. VS Code terminal).

Workflow:
Takes SrcIP and DstIP as input parameters, reads daily summary files from the `summaries/` folder in S3, reads consolidated statistics from `traffic_stats.csv`, and generates a local CSV file combining daily data and global statistics.


## Licence

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Contact

For any questions, you can contact me at `iliesghiar@gmail.com`.

