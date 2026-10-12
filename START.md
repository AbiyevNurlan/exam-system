# 🚀 Layihəni İşə Salma Təlimatı

## 1️⃣ Docker Desktop Quraşdır (əgər yoxdursa)
- [Docker Desktop for Windows](https://www.docker.com/products/docker-desktop/)
- Yüklə, quraşdır və restart et
- Docker Desktop-u işə sal

## 2️⃣ Layihəni Build Et
```powershell
cd "c:\Users\Nurlan\Desktop\Yukselish imtahan\exam-system"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\mvnw.cmd clean package -DskipTests
```

## 3️⃣ Docker ilə İşə Sal (BİR KOMANDA!)
```powershell
docker-compose up -d --build
```

Bu komanda:
✅ PostgreSQL qurur (3 database)
✅ Redis qurur
✅ Prometheus qurur
✅ Grafana qurur
✅ 5 microservice işə salır

## 4️⃣ Yoxla (2-3 dəqiqə gözlə)
```powershell
# Status yoxla
docker-compose ps

# Logları gör
docker-compose logs -f
```

## 5️⃣ Test Et
Brauzerə get:
- **API Gateway**: http://localhost:8080/actuator/health
- **Auth API Docs**: http://localhost:8081/swagger-ui.html
- **Exam API Docs**: http://localhost:8082/swagger-ui.html
- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090

## 🛑 Dayandır
```powershell
docker-compose down
```

## 🔄 Yenidən Başlat
```powershell
docker-compose restart
```

## 📊 Statusu Gör
```powershell
docker-compose ps
docker-compose logs auth-service
docker-compose logs exam-service
```

## ❌ Problem Olarsa
```powershell
# Konteynerləri yenidən qur və başlat (verilənlər bazası saxlanılır)
# DİQQƏT: "docker-compose down -v" İSTİFADƏ ETMƏYİN - bütün verilənləri silir (README: "Database safety")
docker-compose down
docker-compose up -d --build
```

---

## 🎯 Qısa Yol
Bir komanda ilə hər şey:
```powershell
cd "c:\Users\Nurlan\Desktop\Yukselish imtahan\exam-system" && docker-compose up -d --build
```

✨ **HAZIR!** Layihən işləyir!
