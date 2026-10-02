#!/bin/bash
#
# Creates the FleetFlow event topics explicitly.
#
# Relying on `auto.create.topics.enable` would work, but it silently produces
# single-partition topics with replication factor 1 and no partition count worth
# tuning. Declaring them here makes the event topology reviewable in one place and
# lets partitions grow with traffic later.
#
# Partition counts follow the ordering requirement: anything keyed by order id needs
# more than one partition to scale, while the low-volume administrative topics do not.

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
PARTITIONS="${KAFKA_PARTITIONS:-6}"

declare -A TOPIC_PARTITIONS=(
    [order.created]=6
    [inventory.reserved]=6
    [inventory.insufficient]=3
    [delivery.assigned]=6
    [delivery.picked-up]=6
    [delivery.started]=6
    [delivery.completed]=6
    [delivery.failed]=3
    [delivery.cancelled]=3
)

echo "==> waiting for the broker at ${BOOTSTRAP}"
until kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; do
    sleep 3
done

for topic in "${!TOPIC_PARTITIONS[@]}"; do
    partitions="${TOPIC_PARTITIONS[$topic]}"
    echo "==> creating topic '${topic}' with ${partitions} partitions"
    kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" \
        --create --if-not-exists \
        --topic "${topic}" \
        --partitions "${partitions}" \
        --replication-factor 1
done

echo "==> topics now present:"
kafka-topics.sh --bootstrap-server "${BOOTSTRAP}" --list | sort
