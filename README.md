# Bombardier

# Запуск
1. Запустите prometheus и grafana из `docker-compose.yml`.
    ```shell
    docker-compose up -d
    ```

2. После успешного запуска:
   - Grafana будет доступна на `http://localhost:3000/`. Пароль для админа указан указан при создании сервиса (по умолчанию admin/quipy). 
   - SwaggerUI доступен на `http://localhost:1234/swagger-ui/index.html#/bombardier-controller`.
   - Prometheus доступен тут - `http://localhost:9090/`. 
   Grafana создаст директорию grafana/data, в которой будет хранить свои данные. Prometheus - prometheus/data
   
3. После успешного старта обоих docker сервисов, в Grafana должны появиться 
   - DataSource `http://localhost:3000/connections/datasources`
   - Дашборд `http://localhost:3000/dashboards`
   
4. Запустите `DemoServiceApplication.kt`
5. Запустите тестируемый сервис
6. Запустите `run_tests.http`. Это запустит исполнение тестов бомбардира. Подправьте параметы, если нужно
7. Смотрите на метрики и наслаждайтесь

## Docker compose
To run specific version use environment variable `BOMBARDIER_VERSION`:
```shell
BOMBARDIER_VERSION=4.0.8 docker-compose up -d
```

To checkout the logs of the service
```shell
docker-compose logs bombardier
```

To check the metrics of the service
```http request
http://localhost:1234/actuator/prometheus
```

To see and call HTTP API of the service:
```http request
http://localhost:1234/swagger-ui/index.html#/bombardier-controller
```

## Кастомизация через application.yml (студентам не требуется)
Для локальной разработки нужно включить профиль `dev`
```yaml
bombardier:
  # Включение/отключение отправки хедера Authorization bearer
  # Действие метода executeWithAuth будет аналогично методу execute, даже если в первый передать токен
  # По умолчанию: true
  auth-enabled: true
  # Список сервисов, который будет доступен для тестирования
  teams:
    - name: "p03" # serviceName, который указывается в запросах к бомбардьеру
      url: "http://p03:8080" # адрес сервиса
    - name: "p04" # ... и так далее
      url: "http://service-304:8080"
```
(`по умолчанию` = указано в application.yml, кастомизация через профили, подробности [тут](https://www.baeldung.com/spring-profiles))

Основная дока тут – https://andrsuh.notion.site/cd06c475dcf449018749348e16582ee9
