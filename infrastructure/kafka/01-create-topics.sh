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

# The apache/kafka image does not put its bin directory on PATH, so every invocation
# below uses the absolute path. A bare `kafka-topics.sh` fails with "command not found",
# which inside the wait loop below looks like an unreachable broker and hangs forever.
KAFKA_HOME=/opt/kafka
BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
TOPICS="$KAFKA_HOME/bin/kafka-topics.sh"

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

echo "==> verifying the topic CLI is present at ${TOPICS}"
if [[ ! -x "$TOPICS" ]]; then
    echo "FATAL: ${TOPICS} is missing or not executable" >&2
    exit 1
fi

echo "==> waiting for the broker at ${BOOTSTRAP}"
attempt=0
until "$TOPICS" --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if (( attempt % 20 == 0 )); then
        echo "==> still waiting after $((attempt * 3))s"
    fi
    if (( attempt > 60 )); then
        echo "FATAL: broker at ${BOOTSTRAP} never became reachable" >&2
        exit 1
    fi
    sleep 3
done

for topic in "${!TOPIC_PARTITIONS[@]}"; do
    partitions="${TOPIC_PARTITIONS[$topic]}"
    echo "==> creating topic '${topic}' with ${partitions} partitions"
    "$TOPICS" --bootstrap-server "${BOOTSTRAP}" \
        --create --if-not-exists \
        --topic "${topic}" \
        --partitions "${partitions}" \
        --replication-factor 1
done

echo "==> topics now present:"
"$TOPICS" --bootstrap-server "${BOOTSTRAP}" --list | sort