provider "aws" {
  region = "us-east-1"
}

############################
# S3 Bucket
############################
resource "aws_s3_bucket" "tf_iot_input" {
  bucket = "tf-ilies411-iot-traffic-input"
  acl    = "private"
}

############################
# SQS Queues
############################
resource "aws_sqs_queue" "tf_iot_queue_1" {
  name                      = "iot-queue-1"
  visibility_timeout_seconds = 30
}

resource "aws_sqs_queue" "tf_iot_queue_2" {
  name                      = "iot-queue-2"
  visibility_timeout_seconds = 30
}

############################
# IAM Role for Lambda
############################
resource "aws_iam_role" "tf_lambda_role" {
  name = "tf-lambda-iot-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "lambda.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "tf_lambda_basic_execution" {
  role       = aws_iam_role.tf_lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

############################
# Lambda Functions
############################
resource "aws_lambda_function" "tf_summarize_worker" {
  function_name = "summarize-worker"
  role          = aws_iam_role.tf_lambda_role.arn
  handler       = "com.aws.iot.SummarizeWorker::handleRequest" 
  runtime       = "java17"                        
  filename      = "/home/ilies240/Documents/TP_CLOUD_VF/AWS_Cloud_REPO/summarize-worker/target/summarize-worker-1.0-SNAPSHOT-jar-with-dependencies.jar"
}

resource "aws_lambda_function" "tf_consolidator_worker" {
  function_name = "consolidator-worker"
  role          = aws_iam_role.tf_lambda_role.arn
  handler       = "com.aws.iot.ConsolidatorWorker::handleRequest"
  runtime       = "java17"
  filename      = "/home/ilies240/Documents/TP_CLOUD_VF/AWS_Cloud_REPO/consolidator-worker/target/consolidator-worker-1.0-SNAPSHOT-jar-with-dependencies.jar"
}

############################
# Event source mapping (SQS -> Lambda)
############################
resource "aws_lambda_event_source_mapping" "tf_summarize_queue_1" {
  event_source_arn = aws_sqs_queue.tf_iot_queue_1.arn
  function_name    = aws_lambda_function.tf_summarize_worker.arn
}

resource "aws_lambda_event_source_mapping" "tf_consolidator_queue_2" {
  event_source_arn = aws_sqs_queue.tf_iot_queue_2.arn
  function_name    = aws_lambda_function.tf_consolidator_worker.arn
}