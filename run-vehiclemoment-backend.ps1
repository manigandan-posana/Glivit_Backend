$ErrorActionPreference = "Stop"
Set-Location "E:\vehiclemoment\glivt"
$db = "jdbc:mysql://localhost:3306/vehiclemoment_dev?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
java -jar "E:\vehiclemoment\glivt\target\glivt-0.0.1-SNAPSHOT.jar" "--spring.datasource.url=$db" "--spring.jpa.hibernate.ddl-auto=create" "--spring.flyway.enabled=false" "--app.seed-demo=true" "--app.cors.allowed-origins=*" *> "E:\vehiclemoment\glivt\vehiclemoment-backend.log"
