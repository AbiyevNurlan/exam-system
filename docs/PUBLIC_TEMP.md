# Müvəqqəti public rejim (Cloudflare quick tunnel)

Bu rejimdə sayt **bu kompüterdən**, pulsuz Cloudflare quick tunnel vasitəsilə internetə açılır.
Server, domen və ya ödənişli xidmət lazım deyil. Rejim müvəqqəti həll kimi düşünülüb: kiçik
qruplar və sınaq imtahanları üçün. Real server gələndə nəyin dəyişəcəyi sənədin sonunda yazılıb.

Fayllar: [docker-compose.public.yml](../docker-compose.public.yml) (override),
[deploy/public/](../deploy/public/) (nginx konfiqurasiyası), `scripts\init-public-env.ps1`,
`scripts\build-frontend.ps1`, `scripts\go-public.ps1`.

## Necə işləyir

```
Brauzer ──https──> Cloudflare ──tunel──> cloudflared ──> edge (nginx) ──┬─> /            frontend build (SPA)
                                        172.28.1.11      172.28.1.10    ├─> /api/, /auth/  api-gateway (172.28.0.20)
                                                         172.28.0.10    └─> /exam-media/   media-storage (imzalı URL)
```

- **Tək origin.** Sayt, API və media eyni ünvandadır (`https://<təsadüfi-ad>.trycloudflare.com`).
  Frontend API-ni nisbi `/api` yolu ilə çağırır, ona görə brauzerdə CORS tələb olunmur.
- **cloudflared yalnız edge-ə qoşulur.** O, ayrıca `tunnel` şəbəkəsindədir və gateway, verilənlər
  bazası, Redis və ya storage-ə birbaşa çata bilmir.
- **Heç bir port LAN-a və ya internetə açıq deyil.** Tunel konteynerlər arasında işləyir. Host-da
  yalnız yerli yoxlama üçün bunlar var: edge `127.0.0.1:8088` və gateway `127.0.0.1:8080`. Postgres,
  Redis, storage, servislər və Mailpit heç publish olunmur (skriptlər `docker exec` ilə işləyir).
- Diqqət: bu kompüterdə Docker-dən **kənarda** Windows-a quraşdırılmış PostgreSQL 16 servisi
  (`postgresql-x64-16`, `0.0.0.0:5432`, LAN-dan açıqdır) və Redis servisi (yalnız `127.0.0.1:6379`)
  də işləyir. Onlar bu layihəyə aid deyil və public rejim onlara toxunmur. Lazım deyilsə, Windows
  Firewall-da 5432-ni bağlayın və ya servisi dayandırın.
- **Eyni məlumat.** Development ilə eyni Postgres və media həcmləri istifadə olunur. Heç nə
  köçürülmür və yenidən yaradılmır.
- **Production profili.** Servislər `SPRING_PROFILES_ACTIVE=production` ilə işləyir. Postgres və
  Redis güclü parolla qorunur, mail real SMTP-dən gedir.

## Bir dəfəlik hazırlıq

### 1. Gmail App Password

Adi Gmail parolu SMTP üçün **işləmir**. Onu heç bir fayla yazmayın və heç kimlə paylaşmayın.

1. Google hesabında **2 addımlı doğrulamanı** (2-Step Verification) açın.
2. <https://myaccount.google.com/apppasswords> səhifəsində yeni App Password yaradın (ad: məs. "imtahan").
   Google 16 hərfli kod göstərəcək.
3. `.env` faylına (git-ə düşmür) əl ilə əlavə edin:

   ```
   PUBLIC_MAIL_HOST=smtp.gmail.com
   PUBLIC_MAIL_PORT=587
   PUBLIC_MAIL_SMTP_AUTH=true
   PUBLIC_MAIL_STARTTLS=true
   PUBLIC_MAIL_SSL=false
   PUBLIC_MAIL_USERNAME=sizin-unvan@gmail.com
   PUBLIC_MAIL_PASSWORD=<16 hərfli App Password>
   PUBLIC_MAIL_FROM=sizin-unvan@gmail.com
   PUBLIC_MAIL_FROM_NAME=Yüksəliş İmtahan
   ```

Niyə `MAIL_*` yox, `PUBLIC_MAIL_*`: `.env`-də `MAIL_HOST` olsa, development stack-i də Mailpit əvəzinə
Gmail-dən göndərməyə başlayar. Onda test skriptlərinin `@x.test` qeydiyyatları real məktuba çevrilər,
Gmail hesabının reputasiyası isə zərər görər. `PUBLIC_MAIL_*` dəyişənlərini yalnız public rejim
oxuyur (auth-service onları `MAIL_*` kimi alır).

Gmail-in məhdudiyyətləri: şəxsi hesabdan gündə təxminən 500 məktub göndərmək olar, göndərən ünvan
Gmail ünvanının özüdür. İlk məktublar spam qovluğuna düşə bilər.

### 2. Sirlər: `scripts\init-public-env.ps1`

```powershell
.\scripts\init-public-env.ps1
```

- Əvvəlcə `.env`-in git tərəfindən ignore olunduğunu və izlənmədiyini yoxlayır.
- `.env`-də olmayan, boş, `CHANGE_ME` olan və ya zəif olan sirləri yaradır: `JWT_SECRET`,
  `OTP_HMAC_SECRET`, `INTERNAL_SERVICE_SECRET`, `DB_PASSWORD`, `REDIS_PASSWORD` (media açarları yalnız
  heç olmadıqda). Mövcud güclü dəyəri dəyişmir. Heç bir dəyəri ekrana çıxarmır.
- `DB_PASSWORD`-u verilənlər bazasının `postgres` roluna tətbiq edir (`ALTER ROLE`, dəyər əmr
  sətrinə düşmür) və şəbəkə üzərindən yoxlayır. Development stack də bu parolu `.env`-dən oxuyur.
- Admin hesabına toxunmur. `ADMIN_EMAIL`/`ADMIN_PASSWORD` yalnız sistemdə heç bir ADMIN olmadıqda
  istifadə olunur. Belə halda skript onları əl ilə yazmağı tələb edir.
- `PUBLIC_MAIL_*` çatışmırsa, bunu bildirir (exit kodu 2).

Skripti istənilən vaxt yenidən işlətmək olar: ikinci dəfə heç nəyi dəyişmir.

## İşə salmaq, yoxlamaq, dayandırmaq

```powershell
.\scripts\go-public.ps1 start      # yoxlamalar + backup + frontend build + stack + tunel + smoke testlər
.\scripts\go-public.ps1 url        # hazırkı public ünvan
.\scripts\go-public.ps1 status     # konteynerlər, sağlamlıq, tunel, son backup
.\scripts\go-public.ps1 stop       # yalnız edge və tunel dayanır; DB və servislər işləyir
```

`start` aşağıdakılardan hər hansı biri uğursuz olsa, sayt açılmır:

1. `.env` public rejim üçün tamdır (`check-env.ps1`), `PUBLIC_MAIL_*` var, sirlər güclüdür,
   `.env` git-dən kənardadır. Gmail üçün parolun App Password formatında olması da yoxlanır.
2. Verilənlər bazası `DB_PASSWORD`-u qəbul edir.
3. Gateway marşrutları hər iki profildə eynidir (`check-gateway-routes.ps1`). Production gateway
   frontend-in istifadə etdiyi bütün yolları ötürür (`check-gateway-production.ps1`).
4. Backup alınır (`backup.ps1`). Bilərəkdən buraxmaq üçün: `-SkipBackup`.

Bundan sonra frontend production rejimində build olunur, Mailpit dayandırılır, tunel qalxır, stack
public override ilə `up -d` edilir (`down` heç vaxt işlədilmir), 5 servisin sağlamlığı gözlənilir,
public ünvan çap olunur və smoke testlər həmin ünvana qarşı icra olunur: `/`, `/login`, `/register`
və `/admin/exams` 200 qaytarır, tokensiz API 401, `/internal` və `/actuator` 404, imzasız media 404,
başqa saytdan gələn API sorğusu 403, təhlükəsizlik başlıqları mövcuddur.

Digər parametrlər: `-SkipFrontendBuild` (`deploy\public\site`-dakı mövcud build xidmət edir),
`-SkipGatewayCheck`.

**Ünvan hər `start`-da dəyişir.** İştirakçılara linki hər dəfə yenidən göndərmək lazımdır.

### Yalnız frontendi yeniləmək (link dəyişmir)

```powershell
.\scripts\deploy-frontend.ps1
```

**Nə vaxt işlədilir:** yalnız frontend dəyişəndə, yəni frontend repo-nun `main`-inə yeni commit gələndə. Skript heç bir konteyneri dayandırmır və yenidən başlatmır. Tunel də toxunulmaz qalır, ona görə ictimai link eyni qalır.

**Backend dəyişəndə** (servislər, gateway, nginx konfiqurasiyası, `.env`) `.\scripts\go-public.ps1 start` lazımdır. Bu halda link dəyişir.

**Skript nə edir:**
1. Frontend repo-nun `main`-də və təmiz olduğunu yoxlayır, sonra C:-də boş yeri (≥ 1,5 GB).
2. Ayrıca qovluqda build edir: `deploy\public\frontend-staging`.
3. Xidmət edilən versiyanı `deploy\public\frontend-previous\<vaxt>` qovluğuna yedəkləyir. Ən son 3 nüsxə saxlanılır.
4. Faylları sayt heç bir an boş qalmayacaq ardıcıllıqla dəyişir.
5. Yoxlayır:
   - link eynidir və heç bir konteyner yenidən başlamayıb;
   - `/` və `/login` 200 qaytarır;
   - verilən `index.html` yeni build-dir və `no-cache` başlığı ilə gəlir.
6. Yoxlama uğursuz olarsa əvvəlki versiyanı avtomatik qaytarır və bunu bildirir: çıxış kodu 2.

Deploy zamanı link hər 0,3 saniyədən bir yoxlanılır və sonda ən uzun kəsinti göstərilir. Geri qayıtma mexanizmini sınamaq üçün `-TestRollback` açarından istifadə edin: skript deploy edir, yoxlamanı qəsdən uğursuz sayır və köhnə versiyanı qaytarır.

### Development rejiminə qayıtmaq

```powershell
.\scripts\go-public.ps1 stop
.\scripts\up.ps1                   # servisləri docker-compose.yml ilə yenidən yaradır, Mailpit qayıdır
```

## Nə edə bilərsiniz, nə edə bilməzsiniz

Edə bilərsiniz:

- İştirakçılara linki göndərmək: qeydiyyat, email kodu, giriş, imtahan, nəticələr, admin paneli.
- Kiçik qruplar üçün (onlarla, bir neçə yüz nəfərə qədər, növbə ilə) imtahan keçirmək.
- Şəkil, səs və video yükləmək və göstərmək. Fayl 100 MB-a qədər ola bilər (Cloudflare tunelinin
  limiti), API sorğuları isə 5 MB-a qədər.

Edə bilməzsiniz (və ya etməməlisiniz):

- **1000 nəfərin eyni vaxtda imtahan verməsi.** Bu kompüter, ev interneti və pulsuz tunel buna
  yaramır, SLA və zəmanət yoxdur.
- Sabit ünvan və ya öz domeninizi istifadə etmək (quick tunnel hər dəfə yeni təsadüfi ad verir).
- Kompüteri söndürmək, yuxu rejiminə keçirmək və ya Docker Desktop-u bağlamaq: sayt dərhal əlçatmaz olur.
- WebSocket (`/ws`) istifadə etmək: edge onu ötürmür, frontend də hazırda ondan istifadə etmir.
- Grafana/Prometheus-u açmaq: public rejimin hissəsi deyil.

## Məhdudiyyətlər

- Kompüter, Docker Desktop və internet daim işləməlidir. Elektrik və ya internet kəsilsə, sayt dayanır.
- Ünvan hər başlamada dəyişir. Cloudflare quick tunnel-ə heç bir SLA vermir və onu yalnız sınaq
  üçün tövsiyə edir.
- Cloudflare tuneli sorğu gövdəsini 100 MB ilə məhdudlaşdırır: bundan böyük video yüklənmir.
- Hamı bir kompüterin CPU, RAM və upload sürətini bölüşür. Video və səs xüsusilə upload kanalını yükləyir.
- Gmail limiti: gündə təxminən 500 məktub. Kütləvi qeydiyyat günü bu kifayət etməyə bilər.
- Monitoring və alert yoxdur. Problemi siz `status` ilə və ya istifadəçi şikayəti ilə görürsünüz.

## Təhlükəsizlik qərarları

**Real client IP.** Cloudflare `CF-Connecting-IP` başlığını özü qoyur və client-in göndərdiyi
başlığı əvəzləyir. Edge bu başlığa **yalnız** cloudflared konteynerinin sabit ünvanından
(`172.28.1.11`) gəldikdə inanır (`set_real_ip_from`), başqa hallarda bağlantının öz ünvanını
götürür. Gateway-ə tək bir `X-Forwarded-For: <real IP>` ötürür, client-in göndərdiyi bütün
`X-Forwarded-For`/`X-Real-IP`/`Forwarded` başlıqlarını əvəz edir. Gateway isə `X-Forwarded-For`-a
yalnız edge-dən (`GATEWAY_TRUSTED_PROXIES=172.28.0.10`) gəldikdə inanır. Nəticədə rate limit
bucket-ləri real istifadəçi ünvanına görə ayrılır, saxta başlıqla limiti keçmək mümkün olmur.

**CORS.** Tunel ünvanı hər başlamada dəyişdiyi üçün `CORS_ALLOWED_ORIGINS`-ə yazıla bilmir, `"*"`
isə qəbuledilməzdir. Həll belədir: brauzer API-ni saytın öz origin-indən çağırır. Edge `Origin`
başlığı saytın öz ünvanı ilə üst-üstə düşən sorğunu qəbul edir və `Origin`-i silib gateway-ə
ötürür. Başqa `Origin` ilə gələn hər sorğunu isə **403** ilə rədd edir. Beləliklə gateway heç
vaxt cross-site sorğu görmür. Gateway-in siyahısında yalnız yerli yoxlama ünvanı
(`http://127.0.0.1:8088`) var. Tokenlər cookie-də yox, `Authorization` başlığında saxlanıldığı
üçün CSRF riski də yoxdur.

**Edge-də bloklananlar.** `/internal`, `/actuator`, `/fallback`, `/api-docs`, `/swagger-ui`, `/v3`
(hər hansı yol seqmentində, `..` normallaşdırılmasından sonra) → 404. `/ws` ötürülmür. İmzasız
`/exam-media/...` sorğusu 404 alır, yalnız `GET`/`HEAD`/`PUT` icazəlidir.

**Başlıqlar.** `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`,
`Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, `Permissions-Policy`,
`Content-Security-Policy`. Skriptlər yalnız öz origin-dən yüklənir. Kənar mənbə kimi yalnız Google
Fonts və ana səhifədəki şəkil (images.unsplash.com) icazəlidir.

**Media.** Canlı yoxlanılıb: imzalı PUT və GET tunel üzərindən işləyir, pozulmuş imza 403, imzasız
sorğu isə 404 alır. exam-service presigned URL-ləri tunel ünvanı üçün imzalayır
(`MEDIA_S3_PUBLIC_ENDPOINT` = tunel ünvanı, `go-public.ps1 start` hər dəfə təyin edir). Edge
`/exam-media/...` sorğusunu storage-ə **yolu və `Host` başlığını dəyişmədən** ötürür. İmza
host-u və yolu əhatə edir, ona görə imza pozulmur. Edge imzanı yoxlamır, onu storage özü yoxlayır.
Bucket private olaraq qalır.

**OTP limiti.** `/auth/verify-otp`, `resend-otp`, `forgot-password`, `reset-password` hər ünvan üçün
sərt limitlə işləyir: hər sorğu 5 token aparır, defolt `GATEWAY_OTP_REPLENISH=1`,
`GATEWAY_OTP_BURST=50` (bir dəfəyə 10 sorğu, sonra hər 5 saniyədə bir). Defolt dəyişmir. Çox adam
bir məktəb şəbəkəsindən (bir IP-dən) eyni vaxtda kod təsdiqləyirsə, `.env`-də yalnız müvəqqəti
yüngülləşdirin, məs. `GATEWAY_OTP_BURST=250` (bir dəfəyə 50 sorğu) və `go-public.ps1 start`.
Email ünvanı üzrə limitlər (auth-service: 60 saniyə gözləmə, saatda 5 kod) dəyişmir.

## Mail göndərilməsi alınmasa

Bu hal canlı yoxlanılıb: mövcud olmayan SMTP host (`smtp.invalid`) ilə, tunel üzərindən.

- `POST /auth/register` yenə **201** qaytarır və "kod göndərildi" mesajını göstərir. Hesab təsdiqlənməmiş
  qalır, kod verilənlər bazasında yaradılır.
- auth-service məktubu fonda **3 dəfə** (1 s və 3 s fasilə ilə) göndərməyə çalışır, sonra imtina
  edir. Loqda `WARN ... Verification email to t***@x.test was not sent: gave up after 3 attempts`
  yazılır (ERROR yox; ünvan maskalanır, kod heç vaxt yazılmır).
- İstifadəçi xətanı görmür, kodu gözləyir. **"Kodu yenidən göndər"** (`resend-otp`) həmişə 200
  qaytarır. 60 saniyəlik gözləmə müddətindən sonra yeni kod yaradılır və göndərilməsinə yenidən
  cəhd olunur (yoxlanılıb). Bir ünvana saatda ən çox 5 kod göndərmək olar.
- Hesab təsdiqlənməyib, ona görə `login` düzgün parolla 403 `EMAIL_NOT_VERIFIED` qaytarır.
- SMTP düzələndən sonra istifadəçi sadəcə "kodu yenidən göndər" düyməsini basır, heç nə itmir. Lazım
  olsa, admin hesabı əl ilə təsdiqləyə bilər (`POST /auth/admin/users/{id}/verify-email`).
- Göndərilməmiş məktub növbədə saxlanılmır (outbox yoxdur, BACKLOG). Servis yenidən başlasa, cəhdlər itir.

Gmail ilə real göndərmə bu mərhələdə **yoxlanmayıb**: App Password-u siz əlavə edib yoxlayacaqsınız.
Yoxlamaq üçün: `go-public.ps1 start`, sonra public ünvanda öz ikinci e-poçt ünvanınızla qeydiyyat.
Kod 1 dəqiqə ərzində gəlməlidir (spam qovluğuna da baxın).

## Gündəlik yoxlama siyahısı

1. `.\scripts\go-public.ps1 status`: bütün servislər `UP`, tunel ünvanı var, "Media address" tunel
   ünvanı ilə eynidir, son backup 24 saatdan köhnə deyil.
2. Linki telefonda mobil internetdən açın (Wi-Fi-dan yox): ana səhifə, giriş.
3. Gün ərzində ən azı bir qeydiyyat kodunun gəldiyini yoxlayın (spam qovluğuna da baxın).
4. Disk yeri: `backups\` qovluğu (son 14 backup saxlanılır) və Docker həcmləri.
5. `docker logs --since 24h exam-auth-service 2>&1 | Select-String ERROR` (eyni əmri `exam-exam-service`
   və `exam-api-gateway` üçün də işlədin).
6. Windows yeniləmələri kompüteri gecə yenidən başlatmasın (Active hours / yeniləməni təxirə salın).
   Yuxu rejimi söndürülməlidir.

## Problem olanda

| Əlamət | Nə etməli |
|---|---|
| Link açılmır | `status`. Tunel dayanıbsa, `go-public.ps1 start` işlədin (yeni ünvan yaranır, onu yenidən göndərin). |
| `NOT STARTED: ...` | Mesajda yazılan yoxlamanı düzəldin (məs. `init-public-env.ps1`, `.env`-də `PUBLIC_MAIL_*`). |
| Email kodu gəlmir | Spam qovluğuna baxın. `docker logs exam-auth-service 2>&1 \| Select-String -Pattern "mail"` əmrini işlədin. App Password-u yoxlayın və ya yenisini yaradın. İstifadəçi 60 saniyədən sonra "kodu yenidən göndər" düyməsini istifadə edə bilər. |
| Şəkil/video görünmür | `status`: "Media address" tunel ünvanından fərqlidirsə, `go-public.ps1 start` işlədin. |
| 429 Too Many Requests | Bir şəbəkədən çox adam gəlir. OTP üçün `GATEWAY_OTP_BURST`, giriş/qeydiyyat üçün `GATEWAY_AUTH_*` müvəqqəti artırıla bilər. |
| 503 | `status` ilə hansı servisin `UP` olmadığını tapın, sonra `docker logs <konteyner>`. |
| Kompüter yenidən başladı | Docker Desktop açılandan sonra konteynerlər özləri qalxır (`restart: unless-stopped`), amma tunel **yeni ünvan** alır. `go-public.ps1 start` işlədin. |
| Hər şey qarışıb | `go-public.ps1 stop`, sonra `.\scripts\up.ps1` (development). Məlumat itmir. **Heç vaxt** `docker compose down -v` işlətməyin. |

## Gecə backup (Windows Task Scheduler)

Skript bu tapşırığı özü yaratmır. İstəsəniz, PowerShell-i **administrator olaraq** açıb bir dəfə
işlədin:

```powershell
$action  = New-ScheduledTaskAction -Execute 'powershell.exe' `
           -Argument '-NoProfile -ExecutionPolicy Bypass -File "F:\exam-system\scripts\backup.ps1"' `
           -WorkingDirectory 'F:\exam-system'
$trigger = New-ScheduledTaskTrigger -Daily -At 03:00
Register-ScheduledTask -TaskName 'ExamSystem nightly backup' -Action $action -Trigger $trigger `
           -Description 'pg_dump + media, scripts\backup.ps1 (keeps 14)'
```

Yoxlamaq: `Get-ScheduledTaskInfo 'ExamSystem nightly backup'` (LastTaskResult 0 olmalıdır) və
`backups\` qovluğunda yeni tarixli qovluq. Backup-ın bərpa oluna bildiyini həftədə bir dəfə
`.\scripts\restore-test.ps1` ilə yoxlayın. Backup başqa diskə və ya buluda da köçürülməlidir:
kompüter xarab olsa, eyni diskdəki backup da itir.

## Serverə keçid qeydləri

Real serverə (VPS) keçəndə:

- **Tunel → domen və TLS.** `cloudflared` quick tunnel əvəzinə ya adlı Cloudflare tunnel (hesab və
  domen ilə, ünvan sabit olur), ya da serverdə 443 portu və Let's Encrypt istifadə olunur. Ünvan
  sabit olduğu üçün `MEDIA_S3_PUBLIC_ENDPOINT` və `CORS_ALLOWED_ORIGINS` `.env`-də bir dəfə yazılır.
- **edge konfiqurasiyası** (`deploy/public/nginx.conf`) demək olar ki, olduğu kimi qalır.
  `set_real_ip_from` yeni proxy-nin (Cloudflare IP aralıqları və ya load balancer) ünvanına
  dəyişdirilir, HSTS-ə `includeSubDomains` əlavə oluna bilər.
- **docker-compose.production.yml** hələ ayrıca, yarımçıq stack-dir: media storage, Mailpit-siz
  mail seçimi, `REDIS_PASSWORD` və edge yoxdur, həcmin adı da fərqlidir (`exam-system-prod_postgres_data`).
  Serverə keçməzdən əvvəl ya o, bu override-ın öyrəndiklərinə görə yenilənməli, ya da
  `docker-compose.yml + docker-compose.public.yml` əsas götürülməlidir. BACKLOG-a bax.
- **Məlumatın köçürülməsi:** `backup.ps1` ilə alınmış dump və media serverdə `restore-test.ps1`-in
  istifadə etdiyi üsulla bərpa olunur. Serverdə də həcmlər heç vaxt silinmir.
- **Mail:** Gmail əvəzinə öz domenində SMTP provayderi (SPF, DKIM, DMARC ilə), gündəlik limit də
  daha yüksək olur.
- **Backup:** cron/systemd timer və serverdən kənara köçürmə.
- **Monitoring:** Prometheus/Grafana yalnız daxili şəbəkədə qalır, uptime yoxlaması əlavə olunur.
