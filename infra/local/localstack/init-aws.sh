#!/bin/bash
# Bus de eventos local (ADR-002), el mismo patrón que la práctica de mensajería:
#
#   productor ─► SNS urbanmove-domain-events ─┬─► SQS <consumidor>-queue ─► consumidor
#                                             │      (tras 3 fallos) ─► SQS <consumidor>-dlq
#                                             └─► ...
#
# Cada cola recibe SOLO los eventos que consume su servicio, gracias a un filtro por el atributo
# eventType del mensaje. La lista sale de la tabla del catálogo en docs/contracts/asyncapi/README.md.
#
# LocalStack lo ejecuta al arrancar (carpeta /etc/localstack/init/ready.d). En AWS lo crea Terraform.
set -euo pipefail

REGION=us-east-1
TOPIC_ARN=$(awslocal sns create-topic --name urbanmove-domain-events --region "$REGION" \
  --query TopicArn --output text)
echo "Tópico: $TOPIC_ARN"

# create_consumer <nombre> "<eventos separados por espacios>"   (lista vacía = todos los eventos)
create_consumer() {
  local name=$1 events=$2

  local dlq_url dlq_arn queue_url queue_arn
  dlq_url=$(awslocal sqs create-queue --queue-name "$name-dlq" --region "$REGION" --query QueueUrl --output text)
  dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn \
    --region "$REGION" --query Attributes.QueueArn --output text)

  # Tras 3 recepciones sin éxito, el mensaje pasa a la DLQ (AGENTS.md 6.4).
  printf '{"RedrivePolicy":"{\\"deadLetterTargetArn\\":\\"%s\\",\\"maxReceiveCount\\":\\"3\\"}"}' "$dlq_arn" \
    > /tmp/queue-attributes.json
  queue_url=$(awslocal sqs create-queue --queue-name "$name-queue" --region "$REGION" \
    --attributes file:///tmp/queue-attributes.json --query QueueUrl --output text)
  queue_arn=$(awslocal sqs get-queue-attributes --queue-url "$queue_url" --attribute-names QueueArn \
    --region "$REGION" --query Attributes.QueueArn --output text)

  # RawMessageDelivery: la cola recibe el envelope tal cual, sin el sobre de SNS.
  if [ -z "$events" ]; then
    echo '{"RawMessageDelivery":"true"}' > /tmp/subscription.json
  else
    local list="" event
    for event in $events; do
      list="$list${list:+,}\\\"$event\\\""
    done
    printf '{"RawMessageDelivery":"true","FilterPolicy":"{\\"eventType\\":[%s]}"}' "$list" > /tmp/subscription.json
  fi
  awslocal sns subscribe --topic-arn "$TOPIC_ARN" --protocol sqs --notification-endpoint "$queue_arn" \
    --attributes file:///tmp/subscription.json --region "$REGION" > /dev/null
  echo "Cola $name-queue (+ $name-dlq): ${events:-todos los eventos}"
}

create_consumer availability "VehiclePurchased VehicleReserved ReservationCancelled ReservationExpired RideStarted RideFinished VehicleBatteryLow VehicleWithdrawnForMaintenance VehicleReturnedToService RelocationOrderCompleted VehicleRetired"
create_consumer history "VehiclePurchased VehicleReserved ReservationCancelled ReservationExpired RideStarted RideFinished VehicleBatteryLow VehicleWithdrawnForMaintenance VehicleReturnedToService RelocationOrderCreated RelocationOrderCompleted VehicleRetired"
create_consumer notifications "UserRegistered VehicleReserved ReservationCancelled ReservationExpired RideFinished VehicleWithdrawnForMaintenance DemandHotspotDetected RelocationOrderCreated RelocationOrderCompleted"
create_consumer telemetry "VehiclePurchased RideStarted RideFinished VehicleReturnedToService"
create_consumer inventory "VehiclePurchased VehicleWithdrawnForMaintenance VehicleReturnedToService"
create_consumer profiles "UserRegistered"
create_consumer relocation "DemandHotspotDetected"
create_consumer maintenance-detection "VehicleBatteryLow"
create_consumer analytics-ingestion ""

echo "Bus de eventos listo"

# --- Lambda maintenance-detection (bloque B) ----------------------------------------------------
# VehicleBatteryLow -> maintenance-detection-queue -> Lambda -> POST /maintenance-orders.
# ReportBatchItemFailures: solo vuelven a la cola los mensajes que fallaron; tras 3 intentos, a la DLQ.
# Si algo falla aquí, el bus sigue funcionando: solo se avisa (ningún servicio depende de la Lambda).
deploy_maintenance_detection() {
  local jar=/opt/lambdas/maintenance-detection/maintenance-detection.jar
  if [ ! -f "$jar" ]; then
    echo "AVISO: no existe $jar (compila con: cd lambdas && mvn package). Lambda no desplegada."
    return 0
  fi
  printf '{"Variables":{"MAINTENANCE_URL":"http://maintenance:8080","JWT_SECRET":"%s"}}' "$JWT_SECRET" \
    > /tmp/maintenance-detection-env.json
  awslocal lambda create-function --function-name maintenance-detection --region "$REGION" \
    --runtime java21 --memory-size 256 --timeout 30 \
    --handler com.urbanmove.maintenancedetection.interfaces.MaintenanceDetectionHandler::handleRequest \
    --role arn:aws:iam::000000000000:role/LabRole \
    --environment file:///tmp/maintenance-detection-env.json \
    --zip-file "fileb://$jar" > /dev/null
  rm -f /tmp/maintenance-detection-env.json

  local queue_url queue_arn
  queue_url=$(awslocal sqs get-queue-url --queue-name maintenance-detection-queue --region "$REGION" \
    --query QueueUrl --output text)
  queue_arn=$(awslocal sqs get-queue-attributes --queue-url "$queue_url" --attribute-names QueueArn \
    --region "$REGION" --query Attributes.QueueArn --output text)
  awslocal lambda create-event-source-mapping --function-name maintenance-detection --region "$REGION" \
    --event-source-arn "$queue_arn" --batch-size 5 --function-response-types ReportBatchItemFailures > /dev/null
  echo "Lambda maintenance-detection conectada a maintenance-detection-queue"
}
deploy_maintenance_detection || echo "AVISO: no se pudo desplegar la Lambda maintenance-detection"
