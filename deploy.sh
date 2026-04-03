#!/bin/bash
set -euo pipefail

REMOTE_HOST="obruchez@panthalassa.ddns.net"
CONTAINER_NAME="wallbox"
IMAGE_NAME="obruchez/wallbox"
REMOTE_ENV_FILE="\$HOME/wallbox/.env"

# Extract version from build.sbt
VERSION=$(grep 'Docker / version' build.sbt | sed 's/.*"\(.*\)".*/\1/')
if [ -z "$VERSION" ]; then
  echo "Error: could not extract Docker version from build.sbt"
  exit 1
fi

FULL_IMAGE="${IMAGE_NAME}:${VERSION}"

echo "==> Building and pushing ${FULL_IMAGE}..."
sbt docker:publish

echo "==> Deploying to ${REMOTE_HOST}..."
ssh "$REMOTE_HOST" bash -s <<EOF
  set -euo pipefail

  echo "Pulling ${FULL_IMAGE}..."
  docker pull ${FULL_IMAGE}

  if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}\$"; then
    echo "Stopping and removing existing container..."
    docker stop ${CONTAINER_NAME} || true
    docker rm ${CONTAINER_NAME}
  fi

  echo "Starting new container..."
  docker run -d \
    --name ${CONTAINER_NAME} \
    --restart unless-stopped \
    --env-file ${REMOTE_ENV_FILE} \
    ${FULL_IMAGE}

  echo "Container status:"
  docker ps --filter name=${CONTAINER_NAME}
EOF

echo "==> Deployment complete!"