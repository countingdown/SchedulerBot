REM Устанавливаем переменную ENV_FILE
set ENV_FILE=.\.env

REM Переходим в директорию проекта
pushd C:\Users\olegg\javaProjects\botSpring\Scheduler\

REM Проверяем наличие ветки dev и переключаемся на неё
call git fetch origin
call git checkout dev
call git pull origin dev

REM Останавливаем и удаляем старые контейнеры Docker
call docker-compose -f docker-compose.yml --env-file %ENV_FILE% down --timeout=60 --remove-orphans

REM Строим и запускаем контейнеры Docker
call docker-compose -f docker-compose.yml --env-file %ENV_FILE% up --build --detach

REM Возвращаемся в исходную директорию
popd