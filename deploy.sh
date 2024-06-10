#!/bin/bash

ENV_FILE="./.env"

pushd ~/SchedulerBot/ || exit

git checkout dev

git pull origin dev

docker compose -f docker-compose.yml --env-file $ENV_FILE down --timeout=60 --remove-orphans
docker compose -f docker-compose.yml --env-file $ENV_FILE up --build --detach

popd || exit