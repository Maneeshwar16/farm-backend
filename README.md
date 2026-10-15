# 🌾 Innovative Farm Management System — Backend API

This is the Spring Boot backend service for the **Innovative Farm Management System (IFMS)**.

> 📖 **Full System Documentation, Architecture & Workflows:**  
> Please see the master [Root Documentation](../../README.md) for full project architecture, ER diagrams, sequence flows, and deployment blueprints.

---

## 🛠 Tech Stack

- **Framework**: Spring Boot 3.x / 4.x (WebMVC, Data JPA, Validation)
- **Language**: Java 17 / 21 & Kotlin runtime support
- **Database**: MySQL 8.x with Hibernate ORM
- **Containerization**: Docker (Eclipse Temurin JRE multi-stage build)

---

## ⚡ Quick Start

### 1. Database Setup
Create your local database:
```sql
CREATE DATABASE agriculture_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. Configure Credentials
Update `src/main/resources/application.properties` or set environment variables:
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/agriculture_db
spring.datasource.username=root
spring.datasource.password=your_password
```

### 3. Run Application
```bash
# Windows
.\mvnw.cmd spring-boot:run

# Linux / Mac
./mvnw spring-boot:run
```

The API service runs on `http://localhost:8080`.

---

## 🐳 Docker Build

```bash
docker build -t farm-backend .
docker run -p 8080:8080 -e DB_URL="jdbc:mysql://host.docker.internal:3306/agriculture_db" farm-backend
```
