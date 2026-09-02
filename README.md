# tus-java-server-spring-demo
Tus java server demo using Spring Boot that uses the [tus-java-server library](https://github.com/tomdesair/tus-java-server/) and the [Uppy file uploader](https://uppy.io/) in order to easily provide asynchronous and resumable file uploads in Spring.

To build and run this demo, execute the following commands:

```
$ git clone https://github.com/tomdesair/tus-java-server-spring-demo.git
$ cd tus-java-server-spring-demo
$ mvn clean package
$ java -jar spring-boot-rest/target/spring-boot-rest-0.0.1-SNAPSHOT.jar
```

Then visit http://localhost:8080/test/ in your browser and try to upload a file using the Uppy file uploader.

## Que?
* Module `spring-boot-rest` provides the API backend for large-file transfer:
    1. Class `me.desair.spring.transfer.App` is the Spring Boot application entry point enabling scheduling and core configuration.
    1. The `me.desair.spring.transfer.api.TransferController` provides the transfer and chunk endpoints (`/api/transfers`), delegating business orchestration to `me.desair.spring.transfer.application.TransferService`.
    1. The `me.desair.spring.transfer.api.HealthController` provides the `/health` endpoint.
    1. Class `me.desair.spring.tus.client.UploadScript` was removed during the refactor.
    1. File `spring-boot-rest/src/main/resources/public/index.html` contains the demo page.

* Frontend:
    1. Module `frontend` contains the React + TypeScript + Vite web client.
    2. Legacy static demo assets and styles remain accessible under `spring-boot-rest/src/main/resources/public/`.