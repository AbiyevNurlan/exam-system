# Layihə xəritəsi (PROJECT_MAP)

Bu sənəd backendin "canlı xəritəsi"dir: nə var, harada yerləşir, necə işləyir, nə hazırdır və nə yarımçıqdır.
Hər tapşırığın sonunda, commit-dən əvvəl yenilənir (qayda: [PROJECT_RULES.md](PROJECT_RULES.md), "Layihə xəritəsi
qaydası"). Yoxlama skripti: `scripts\check-project-map.ps1`.

Digər sənədlər burada təkrar yazılmır, yalnız istinad olunur:
[BACKLOG.md](BACKLOG.md) (açıq işlər), [PROJECT_RULES.md](PROJECT_RULES.md) (qaydalar),
[AUDIT_GAP.md](AUDIT_GAP.md) (Moodle və başqaları ilə müqayisə), [PUBLIC_TEMP.md](PUBLIC_TEMP.md) (ictimai rejim),
`scripts/regression/README.md` (regression skriptləri).

| | |
|---|---|
| Son yenilənmə | 2026-10-22 (Bakı) |
| Analiz olunan kod | `cb19e65` (`feat/avatar` filialının son commit-i; `main` = `45138df`) |
| Canlı baza | exam_db V23, exam_auth V8 (2026-10-22, yükləmədən sonra) |
| Frontend | `exam-frontend`, filial `feat/exam-lock-audit` (`79f7757`, `main`-dən 1 commit irəli; `main` = `ff97fb4`) |

Yolların qısaltmaları (AUDIT_GAP-dakı kimi):
`ES/` = `exam-service/src/main/java/com/examportal/exam/`, `AS/` = `auth-service/src/main/java/com/examportal/auth/`,
`CM/` = `common/src/main/java/com/examportal/common/`, `GW/` = `api-gateway/src/main/java/com/examportal/gateway/`,
`FE/` = `exam-frontend/src/` (ayrıca repo: `C:\Users\Nurlan\Desktop\Yukselish imtahan\exam-frontend`).

---

## 0. Ümumi mənzərə (bir səhifə)

**Bu nədir.** "Yüksəliş" onlayn imtahan platformasının backendi. Admin və müəllim imtahan hazırlayır, iştirakçılar
qeydiyyatdan keçir, qruplara qoşulur, imtahan verir, nəticə görür. Açıq suallar əl ilə yoxlanılır. Canlı izləmə və
analitika var.

**Necə qurulub.** Java 21 + Spring Boot 3.2 (WebFlux, reaktiv) mikroservislər, Docker Compose ilə işləyir:

```
Brauzer
  │  (ictimai rejimdə: https://<təsadüfi>.trycloudflare.com)
  ▼
cloudflared ──► edge (nginx, frontend faylları) ──► api-gateway :8080 ──┬─► auth-service :8081 ─┐
                       │                                                └─► exam-service :8082 ─┤
                       └─► /exam-media/ (imzalı link) ─► media-storage (SeaweedFS) :8333 ◄──────┘
                                                                                                 │
                        postgres :5432 (exam_auth, exam_db, exam_monitoring) + redis :6379 ◄─────┘
auth-service ◄──► exam-service: /internal/** (X-Internal-Secret başlığı ilə)
```

- **Gateway** qapıdır: tokeni yoxlayır, sorğu limitini (rate limit) tətbiq edir, sorğunu lazım olan servisə ötürür.
- **auth-service** hesablardır: qeydiyyat, email kodu (OTP), giriş, parol, müəllim hesabları.
- **exam-service** qalan hər şeydir: imtahan, sual, sessiya, nəticə, qrup, media, analitika, audit.
- **monitoring-service** və **websocket-service** boş skeletdir: işə düşür, amma heç bir iş görmür.

**İndiki vəziyyət, qısa:**
- `main`-də B2–B12a hazırdır. B13 (müəllimlərin bir-birindən ayrılması) və profil şəkli (avatar) birləşməmiş
  filiallardadır. Canlı sayt isə artıq B13 kodu ilə işləyir (canlı bazada exam V19 və auth V8 tətbiq olunub).
- Yeni sual növləri hazırdır, amma `main`-də deyil: Uyğunlaşdırma, Sıralama, Qısa cavab və Diapazon (exam V21, V22),
  backend `feat/question-types`-da, frontend `exam-frontend`-in eyni adlı filialında (2026-10-22).
- 224 avtomatik test var, hamısı keçir (2026-10-21).
- Ən böyük risklər: canlı sayt və development eyni bazanı işlədir; ictimai saytda görünən 16 aktiv imtahanın
  hamısı `T-` test imtahanıdır; backup-lar yalnız eyni kompüterdədir və gecə backup tapşırığı yoxdur; sayt düşəndə
  onu qaldıran nəzarətçi söndürülüb; frontend müəllimin iş axınını (B13) hələ dəstəkləmir.

---

## 1. a) Arxitektura

### Nə var

| Hissə | Vəzifəsi | Konteyner portu | Development-də host | İctimai rejimdə host | Asılıdır | Harada |
|---|---|---|---|---|---|---|
| api-gateway | Qapı: JWT yoxlaması, rate limit, marşrutlar, circuit breaker | 8080 | `0.0.0.0:8080` | `127.0.0.1:8080` | auth, exam, redis | `api-gateway/` |
| auth-service | Hesablar, OTP, giriş, tokenlər, müəllim hesabları, profil | 8081 | `0.0.0.0:8081` | yoxdur | postgres (exam_auth), redis, SMTP | `auth-service/` |
| exam-service | İmtahan, sessiya, nəticə, qrup, media, analitika, audit | 8082 | `0.0.0.0:8082` | yoxdur | postgres (exam_db), redis, media-storage, auth (daxili) | `exam-service/` |
| monitoring-service | Skelet (yalnız `MonitoringServiceApplication`) | 8083 | `0.0.0.0:8083` | yoxdur | postgres (exam_monitoring) | `monitoring-service/` |
| websocket-service | Skelet (`/ws/exam/**`, `/ws/notifications/**` handler-i, heç kim istifadə etmir) | 8084 | `0.0.0.0:8084` | yoxdur | redis | `websocket-service/` |
| common | Ortaq kod: DTO, entity, JWT filtri, xəta cavabı | – | – | – | – | `common/` |
| postgres 16-alpine | Bütün bazalar, bir konteyner `exam-postgres`, həcm `exam-system_postgres_data` | 5432 | `0.0.0.0:5432` (compose faylında) | yoxdur | – | `docker-compose.yml`, `init-scripts/` |
| redis 7-alpine | Rate limit, `ReadCache`, token ləğvi | 6379 | `0.0.0.0:6379`, parolsuz | yoxdur, parollu | – | `docker-compose.yml` |
| mailpit v1.20 | Development poçt qutusu (heç nə göndərmir) | 1025, 8025 | `127.0.0.1:1025`, `127.0.0.1:8025` | söndürülür (`dev-mail` profili) | – | `docker-compose.yml` |
| media-storage (SeaweedFS 4.48) | Şəkil, səs, video (S3 API) | 8333 | `127.0.0.1:8333` | yoxdur | – | `docker-compose.yml` |
| edge (nginx 1.30.5) | Frontend faylları və `/api/`, `/auth/`, `/exam-media/` ötürülməsi | 80 | – | `127.0.0.1:8088` | gateway, media | `deploy/public/nginx.conf` |
| cloudflared 2026.9.3 | Cloudflare quick tunnel (hesabsız, təsadüfi ünvan) | – | – | heç nə | edge | `docker-compose.public.yml` |

Qeyd: bu kompüterdə Windows-un öz PostgreSQL 16 xidməti də 5432-də, öz Redis-i 6379-da qulaq asır (bizim deyil).
Development compose-dakı `5432:5432` və `6379:6379` bununla toqquşa bilər (development rejimində yoxlanılmadı:
analiz zamanı ictimai rejim açıq idi). İctimai rejimdə Postgres və Redis host-a ümumiyyətlə açılmır.
Rate limit rəqəmləri aşağıda development faylından (`application.yml`) götürülüb.

### Servislər arası çağırışlar

| Kim | Kimi çağırır | Nə üçün | Vaxt limiti | Harada |
|---|---|---|---|---|
| gateway | auth-service | `/auth/**`, `/api/admin/instructors/**` | 10 s (circuit breaker) | `api-gateway/src/main/resources/application.yml` |
| gateway | exam-service | 14 yol prefiksi (`/api/exams/**`, `/api/admin/groups/**`, `/api/me/**` və s.) | 60 s | eyni fayl |
| gateway | websocket, monitoring | `/ws/**`, `/api/monitoring/**` (hər ikisi boş servisdir) | – | eyni fayl |
| exam-service | auth-service | ad və email axtarışı, iştirakçı siyahısı, statistik rəqəmlər, profil, kart, admin auditi (`/internal/users/*`, `/internal/audit/admin`) | 5 s (böyük siyahılar 15 s) | `ES/client/UserDirectoryClient.java` |
| auth-service | exam-service | girişdən və email təsdiqindən sonra gözləyən qrup dəvətlərinin tətbiqi (`/internal/group-invites/apply`) | 2 s | `AS/client/GroupInviteClient.java` |
| auth-service | exam-service | müəllim siyahısı üçün qrup, üzv və imtahan sayları (`/internal/instructors/figures`) | 2 s | `AS/client/InstructorFiguresClient.java` |

### Planlı işlər (scheduler)

| Servis | İş | Nə vaxt | Harada |
|---|---|---|---|
| exam-service | vaxtı çatmış imtahanı ACTIVE edir, bitmişi COMPLETED edir, vaxtı bitmiş sessiyanı avtomatik təhvil verir | hər 60 s | `ES/service/ExamSchedulerService.java` |
| exam-service | yaddaşdakı keşin təmizlənməsi | hər saat | eyni fayl |
| exam-service | təsdiqlənməmiş yükləmələr (5 dəq), istifadəsiz media (30 dəq, 14 gündən sonra silinir) | | `ES/service/MediaCleanupJob.java` |
| auth-service | köhnə OTP-ləri silir (7 gün), təsdiqlənməmiş hesabları söndürür (14 gün, silmir) | hər gün 03:30 və hər başlanğıcda | `AS/service/AccountCleanupJob.java` |

### Compose faylları

| Fayl | Nədir | Vəziyyəti |
|---|---|---|
| `docker-compose.yml` | Development stack (layihə adı `exam-system`) | işləyir |
| `docker-compose.public.yml` | Development-in üstünə ictimai rejim (production profili, real SMTP, edge, cloudflared) | işləyir, indi açıqdır |
| `docker-compose.production.yml` | Server üçün nəzərdə tutulub (`exam-system-prod`) | yarımçıq: BACKLOG "Ops" bölməsi |
| `docker-compose.performance.yml` | Yük testi (`exam-system-perf`, portlar yalnız 127.0.0.1) | işlədilməyib |

### İctimai rejim (Cloudflare tunnel)

- `scripts\go-public.ps1 start` əvvəlcə yoxlayır (.env, parollar, gateway marşrutları, backup), sonra frontend
  build edir, Mailpit-i dayandırır, stack-i ictimai override ilə qaldırır, ünvanı çap edir. `stop` yalnız edge və
  tuneli dayandırır. Ətraflı: [PUBLIC_TEMP.md](PUBLIC_TEMP.md).
- `scripts\watch-public.ps1` sayt düşəndə onu yeni tunellə qaldırır. Windows-da "Yukselis watch-public" tapşırığı
  var, amma **söndürülüb (Disabled)**. Son işləməsi 2026-10-20 10:03 idi. `deploy\public\state\watch.pid`
  2026-10-16-dan qalıb, o proses artıq yoxdur. Yəni hazırda nəzarətçi işləmir.
- Quick tunnel-in ünvanı hər başlanğıcda dəyişir; yeni linki iştirakçılara əl ilə göndərmək lazımdır.

### Texniki qeyd

- İctimai rejimin şəbəkələri: `public` 172.28.0.0/24 (edge .10, gateway .20, media .30), `tunnel` 172.28.1.0/24
  (edge .10, cloudflared .11). Gateway `X-Forwarded-For`-a yalnız edge-dən (172.28.0.10) inanır.
- İctimai rejimdə bağlantı hovuzları: exam 50, auth 25, monitoring 10; Postgres-in limiti 100
  (`docker-compose.public.yml`, 2026-10-16 hadisəsi).
- Gateway-də ölü marşrutlar: `/api/questions/**`, `/api/results/**`, `/api/sessions/**` exam-service-ə gedir, amma
  orada belə controller yoxdur (404).

### Nə hazırdır, nə yarımçıqdır

- Hazır: development və ictimai rejim; daxili çağırışlar gizli başlıqla; vaxt limitləri gateway-də servislərdən uzundur.
- Yarımçıq: production compose; monitoring və websocket servisləri boşdur; nəzarətçi tapşırığı söndürülüb;
  sabit domen və server yoxdur.

---

## 2. b) Verilənlər bazası

### Nə var

Bir Postgres konteyneri, üç baza:

| Baza | Kim istifadə edir | Ölçü (2026-10-21) | Flyway |
|---|---|---|---|
| `exam_auth` | auth-service | 32 MB | var |
| `exam_db` | exam-service | 27 MB | var |
| `exam_monitoring` | monitoring-service | 7.5 MB | yoxdur (cədvəl yoxdur) |

Qeyd: tapşırıqda "auth_db" deyilir; bazanın real adı `exam_auth`-dur.

Sxemi yalnız Flyway dəyişir: `auth-service/src/main/resources/db/migration/` və
`exam-service/src/main/resources/db/migration/`. Tətbiq olunmuş migrasiya heç vaxt dəyişdirilmir
(PROJECT_RULES, "Absolute rules").

### Flyway versiyaları

| | Kodda (`main`) | Canlı bazada (2026-10-22) |
|---|---|---|
| exam_auth | V1–V8 (8 fayl) | V8 |
| exam_db | V1–V23 (23 fayl) | V23 |

**exam_auth migrasiyaları** (canlıda tətbiq tarixi):
V1 baseline (10-12) · V2 email OTP (10-12) · V3 7 rəqəmli iştirakçı kodu (10-16) · V4 iştirakçı profili və kateqoriyalar
(10-16) · V5 kod dəyişməz olsun, trigger (10-16) · V6 Azərbaycan əlifbası collation `az_ci` (10-19) · V7 sıralama
indeksləri (10-19) · V8 müəllim hesabları: müvəqqəti parol, `must_change_password`, `last_login_at` (10-19).

**exam_db migrasiyaları**:
V1 baseline (10-12) · V2 balların genişlənməsi (10-12) · V3 qruplar və auditoriya (10-12) · V4 sual mediası (10-12) ·
V5 istifadəçi üzrə sessiya indeksi (10-13) · V6 fırıldaq hadisələri (10-13) · V7 gündəlik cəhd limiti (10-13) ·
V8 əl ilə yoxlama (10-13) · V9 monitorinq indeksləri (10-13) · V10 qoşulma linkləri (10-13) · V11 admin icmal
indeksləri (10-14) · V12 qrupun sahibi `owner_id` (10-15) · V13 istifadəçi üzrə qoşulma sorğuları (10-15) · V14 iştirakçı
kartı indeksləri (10-16) · V15 `az_ci` (10-19) · V16 bloklar (10-19) · V17 tarixçə və snapshot (10-19) · V18 qrup adı
sahib üzrə unikal (10-19) · V19 müəllim limitləri (10-19) · V20 profil şəkli (10-22) · V21 uyğunlaşdırmanın sağ tərəfi
(`match_text`, 10-22) · V22 Diapazon (`questions.scale_*`, CHECK ilə, 10-22) · **V23 sessiyanın blokları
(`session_block_progress`, 10-22)**.

### exam_auth cədvəlləri (8)

| Cədvəl | Nə saxlayır | Əsas sütunlar | Əlaqə | İndeks və trigger | Sətir |
|---|---|---|---|---|---|
| `users` | Bütün hesablar | `id` UUID, `email`, `password_hash`, ad, soyad, telefon, `role`, `email_verified`, `account_locked`, `locked_until`, `active`, `participant_code`, `must_change_password`, `temp_password_expires_at`, `last_login_at` | – | 14 indeks (`ux_users_email_lower`, `uq_users_participant_code`, iştirakçı sıralaması üçün qismən indekslər, `idx_users_instructors`); triggerlər `trg_users_participant_code_assign`, `trg_users_participant_code_immutable` | 1072 |
| `refresh_tokens` | Yeniləmə tokenləri (7 gün) | `token` unikal, `revoked`, `expires_at` | `users` (CASCADE) | 4 | 993 |
| `email_otp` | Email kodları, yalnız HMAC | `code_hash`, `purpose`, `attempts`, `consumed_at` | `users` (CASCADE) | `idx_email_otp_user_purpose_created` | 1003 |
| `participant_profiles` | İştirakçı profili | `category`, `institution`, fakültə, kurs, iş yeri, şəhər, `birth_date`, `version` | `users` (CASCADE), `participant_categories` | `uq_participant_profiles_user`, kateqoriya, müəssisə | 19 |
| `participant_categories` | Kateqoriyalar siyahısı | `code`, `sort_order`, `active` | – | PK | 6 |
| `admin_audit_log` | Hesablarla bağlı audit | `admin_user_id`, `action`, `target_user_id`, `details` | – | aktor+vaxt, vaxt, hədəf | 65 |
| `password_reset_tokens` | İstifadə olunmur (parol bərpası OTP ilədir) | | `users` | | 4 |
| `email_verification_tokens` | İstifadə olunmur | | `users` | | 0 |

### exam_db cədvəlləri (canlıda 23, kodda V20 ilə 24)

| Cədvəl | Nə saxlayır | Əsas sütunlar | Əlaqə | Sətir |
|---|---|---|---|---|
| `exams` | İmtahan | `title`, `status`, `start_time`, `end_time`, `duration_minutes`, `access_type`, `max_attempts`, `max_attempts_per_day`, `created_by` (sahib), `timing_mode`, blok ayarları | `categories` | 209 |
| `exam_blocks` | Bloklar (hər imtahanda ən azı "Əsas blok") | `position`, `title`, `time_limit_minutes`, `break_after_minutes`, `free_navigation`, `show_result_to_participant` | `exams` (CASCADE) | 233 |
| `questions` | Suallar | `question_text`, `question_type`, `points`, `order_index`, `block_id` | `exams` (CASCADE), `exam_blocks` | 745 |
| `question_options` | Variantlar və cavab açarı | `option_text`, `is_correct`, `order_index` | `questions` (CASCADE) | 1743 |
| `session_block_progress` | Sessiyanın bloklar üzrə yolu (B12b): hər daxil olduğu blok üçün bir sətir | `position`, `block_id`, `started_at`, `deadline`, `finished_at`, `break_until`, `cursor` | `exam_sessions` (CASCADE) | 0 |
| `exam_sessions` | Cəhdlər | `session_id`, `user_id`, `status`, `score`, `total_points` (maksimum), `percentage`, `passed`, `grading_status` | `exams` | 208 |
| `user_answers` | Cavablar | `selected_option_ids`, `text_answer`, `points_earned`, `needs_manual_review`, `grade_version` | `exam_sessions` (CASCADE), `questions` | 524 |
| `session_fraud_events` | Brauzerin bildirdiyi pozuntular | `type`, `occurred_at`, `details` | `exam_sessions` (CASCADE) | 247 |
| `exam_audience` | GROUP imtahanın qrupları | `exam_id`, `group_id` | `exams` (CASCADE), `user_groups` | 61 |
| `user_groups` | Qruplar | `name`, `owner_id`, `active` | – | 80 |
| `user_group_members` | Üzvlük | `group_id`, `user_id`, `source` (ADMIN, CSV, BULK, LINK) | `user_groups` | 160 |
| `pending_group_invites` | Hələ hesabı olmayan email-ə dəvət | `email_normalized` | `user_groups` | 11 |
| `group_join_links` | Qoşulma linki | `code`, `enabled`, `requires_approval`, `max_uses`, `uses`, `expires_at` | `user_groups` | 28 |
| `group_join_requests` | Təsdiq gözləyən qoşulma | `status`, `decided_by`, `decided_at` | `user_groups` | 30 |
| `group_audit_log` | exam-service-in auditi (qrup, imtahan, qiymət, eksport) | `actor_id`, `action`, `group_id`, `target_user_id`, `details` | – | 621 |
| `media_assets` | Yüklənmiş fayllar | `owner_id`, `kind`, `storage_key`, `status`, `size_bytes` | – | 68 |
| `question_media`, `option_media` | Media ilə sual və variantın əlaqəsi | `position`, `max_plays` | `questions`/`question_options` (CASCADE), `media_assets` | 14, 9 |
| `media_play_counts` | Səs və video neçə dəfə açılıb | `session_id`, `media_id`, `plays` | `exam_sessions`, `media_assets` | 0 |
| `exam_revisions` | Dəyişiklik tarixçəsi (yalnız əlavə olunur) | `revision_no`, `save_id`, `actor_*`, `action`, `changes` JSONB, `sensitive`, `reason`, `summary_key` | – (imtahan silinəndə də qalır) | 561 |
| `exam_revision_counters` | Hər imtahanın son revizyon nömrəsi | `last_revision` | – | 156 |
| `exam_published_snapshots` | Hər dərcin tam surəti | `version_no`, `snapshot` JSONB, `sha256` | – | 91 |
| `instructor_limits` | Müəllimin öz limitləri (boş = default) | `max_groups`, `max_members`, `max_exams`, `max_media_bytes` | – | 1 |
| `categories` | İmtahan kateqoriyası (API-si yoxdur) | `name`, `parent_id` | özünə | 0 |
| `user_avatars` (V20, canlıda yoxdur) | Profil şəkli | `user_id`, `storage_key`, `pending_key` | – | – |

Triggerlər (exam_db): `trg_exams_main_block` (yeni imtahana "Əsas blok"), `trg_questions_default_block`,
`trg_exam_revisions_append_only` və `_no_truncate`, `trg_exam_published_snapshots_append_only` və `_no_truncate`
(tarixçəni dəyişmək və silmək olmur).

### Texniki qeyd

- İki baza arasında foreign key yoxdur: `exams.created_by`, `exam_sessions.user_id` və s. sadəcə UUID-dir.
  Hesab silinsə, imtahan və sessiyalar qalır (BACKLOG "Platform").
- Sətir sayları 2026-10-21-də canlı bazadan `count(*)` ilə alınıb; 996 hesab `@x.test`, 153 imtahan `T-`
  test məlumatıdır (bax: bölmə 12).
- İndekslərin tam siyahısı: `docker exec exam-postgres psql -U postgres -d exam_db -c "\di"`.

### Nə hazırdır, nə yarımçıqdır

- Hazır: Flyway hər iki servisdə; tarixçə trigger ilə qorunur; iştirakçı kodu bazada təyin olunur.
- Yarımçıq: V20 canlıya tətbiq olunmayıb; V21 və V22 yalnız `feat/question-types`-dadır; `exam_monitoring` boşdur; iki köhnə token cədvəli
  istifadə olunmur; `categories`-in API-si yoxdur.

---

## 3. c) Endpoint-lər

### Rəqəmlər

| Servis | Reyestrdə | Kodda (annotasiya) | Siniflər |
|---|---|---|---|
| auth-service | 29 | 29 | PUBLIC 8, OWN_DATA 4, ADMIN_ONLY 7, INTERNAL 10 |
| exam-service | 91 | 91 | OWNER_SCOPED 63, STUDENT_ONLY 12, OWN_DATA 8, ADMIN_ONLY 5, INTERNAL 2, PUBLIC 1 |
| api-gateway | – | 3 (`/fallback/auth`, `/fallback/exam`, `/fallback/monitoring`, yalnız daxili yönləndirmə) | – |
| monitoring, websocket | – | 0 | – |
| **Cəmi** | **120** | **120** | |

Reyestr faylları: `auth-service/src/test/resources/endpoint-access.txt`, `exam-service/src/test/resources/endpoint-access.txt`.
Hər servisin `EndpointRegistryTest`-i reyestri real kodla (RequestMappingHandlerMapping) və real təhlükəsizlik
zənciri ilə tutuşdurur. 2026-10-21-də hər ikisi keçdi, yəni reyestr kodla tam üst-üstə düşür.

Siniflərin mənası: ADMIN_ONLY yalnız admin; OWNER_SCOPED admin hamısını, müəllim yalnız özününkünü (başqasınınkı 404);
OWN_DATA hər daxil olmuş istifadəçi, yalnız özünün; STUDENT_ONLY iştirakçı axını (hər daxil olmuş istifadəçi
iştirakçı kimi öz sessiyaları üzərində); PUBLIC giriş tələb etmir; INTERNAL yalnız servisdən servisə, gizli başlıq
olmadan 404.

"FE" sütunu: frontend bu endpoint-i çağırırmı (bölmə 13).

### auth-service (29)

| Metod | Yol | Vəzifə | Sinif | FE |
|---|---|---|---|---|
| POST | `/auth/register` | Qeydiyyat (həmişə STUDENT, token vermir) | PUBLIC | bəli |
| POST | `/auth/verify-otp` | Email kodunu təsdiq edir, token verir | PUBLIC | bəli |
| POST | `/auth/resend-otp` | Yeni kod göndərir | PUBLIC | bəli |
| POST | `/auth/login` | Giriş | PUBLIC | bəli |
| POST | `/auth/refresh` | Tokeni yeniləyir | PUBLIC | bəli |
| POST | `/auth/forgot-password` | Parol bərpası kodu göndərir | PUBLIC | bəli |
| POST | `/auth/reset-password` | Kodla yeni parol qoyur | PUBLIC | bəli |
| GET | `/auth/health` | Sağlamlıq yoxlaması | PUBLIC | yox (b) |
| POST | `/auth/logout` | Çıxış | OWN_DATA | bəli |
| GET | `/auth/me` | Öz hesabı (iştirakçı kodu daxil) | OWN_DATA | bəli |
| PUT | `/auth/profile` | Ad, soyad, telefon | OWN_DATA | bəli |
| POST | `/auth/change-password` | Parolu dəyişir, köhnə tokenləri bitirir | OWN_DATA | bəli |
| GET | `/auth/admin/users` | İstifadəçi axtarışı | ADMIN_ONLY | yox (a) |
| POST | `/auth/admin/users/{id}/unlock` | Kilidli hesabı açır | ADMIN_ONLY | yox (a) |
| POST | `/auth/admin/users/{id}/verify-email` | Email-i admin təsdiq edir | ADMIN_ONLY | yox (a) |
| POST | `/api/admin/instructors` | Müəllim hesabı yaradır (müvəqqəti parol bir dəfə göstərilir) | ADMIN_ONLY | yox (a) |
| GET | `/api/admin/instructors` | Müəllimlər, rəqəmləri ilə | ADMIN_ONLY | yox (a) |
| PATCH | `/api/admin/instructors/{id}` | Müəllimi dəyişir, söndürür | ADMIN_ONLY | yox (a) |
| POST | `/api/admin/instructors/{id}/reset-password` | Müvəqqəti parolu yeniləyir | ADMIN_ONLY | yox (a) |
| POST | `/internal/audit/admin` | Admin auditini exam-service-ə verir | INTERNAL | – (b) |
| GET | `/internal/users/{userId}/card` | İştirakçı kartı | INTERNAL | – (b) |
| POST | `/internal/users/participants` | İştirakçı siyahısının səhifəsi | INTERNAL | – (b) |
| POST | `/internal/users/participants/ids` | Filtrə uyğun id-lər | INTERNAL | – (b) |
| POST | `/internal/users/participants/summary` | İştirakçı xülasəsi | INTERNAL | – (b) |
| GET | `/internal/users/{userId}/profile` | Profil | INTERNAL | – (b) |
| PUT | `/internal/users/{userId}/profile` | Profil yazılması | INTERNAL | – (b) |
| POST | `/internal/users/search` | Ad və email axtarışı | INTERNAL | – (b) |
| POST | `/internal/users/stats` | Platforma rəqəmləri | INTERNAL | – (b) |
| POST | `/internal/users/lookup` | Id-lərə görə ad və email | INTERNAL | – (b) |

### exam-service (91)

| Metod | Yol | Vəzifə | Sinif | FE |
|---|---|---|---|---|
| GET | `/api/exams/{examId}/analytics/questions` | Sual analizi | OWNER_SCOPED | bəli |
| GET | `/api/exams/{examId}/analytics/overview` | İmtahan icmalı | OWNER_SCOPED | bəli |
| GET | `/api/analytics/overview` | Platforma icmalı | ADMIN_ONLY | bəli |
| GET | `/api/admin/audit` | Audit jurnalı (müəllimə yalnız öz hərəkətləri) | OWNER_SCOPED | bəli (yalnız admin menyusu) |
| POST | `/api/exams` | İmtahan yaradır | OWNER_SCOPED | bəli |
| PUT | `/api/exams/{id}` | İmtahanı yeniləyir (yerində, id-lər qalır) | OWNER_SCOPED | bəli |
| GET | `/api/exams/{id}/readiness` | Dərcə hazırlıq yoxlaması | OWNER_SCOPED | yox (a) |
| POST | `/api/exams/{id}/clone` | Qaralama nüsxə | OWNER_SCOPED | yox (a) |
| DELETE | `/api/exams/{id}` | Silir (sessiya varsa 409, arxivlə) | OWNER_SCOPED | bəli |
| GET | `/api/exams/{id}` | İmtahan | OWN_DATA | bəli |
| GET | `/api/exams/my` | Öz imtahanlarım | OWNER_SCOPED | bəli |
| GET | `/api/exams/manage` | İdarə siyahısı | OWNER_SCOPED | bəli |
| GET | `/api/exams/available` | İştirakçı üçün mövcud imtahanlar | STUDENT_ONLY | bəli |
| GET | `/api/exams/my-sessions` | Öz cəhdlərim | STUDENT_ONLY | bəli |
| POST | `/api/exams/{id}/start` | Başlayır (cəhd limiti atomik) | STUDENT_ONLY | bəli |
| GET | `/api/exams/session/{sessionId}/questions` | Sessiyanın sualları | STUDENT_ONLY | bəli |
| GET | `/api/exams/session/{sessionId}/media/{mediaId}` | Media linki (oynatma limiti) | STUDENT_ONLY | bəli |
| GET | `/api/exams/session/{sessionId}/answers` | Saxlanmış cavablar | STUDENT_ONLY | bəli |
| POST | `/api/exams/session/{sessionId}/fraud-event` | Pozuntu qeydi | STUDENT_ONLY | bəli |
| POST | `/api/exams/session/{sessionId}/answer` | Cavab | STUDENT_ONLY | bəli |
| POST | `/api/exams/session/{sessionId}/submit` | Təhvil | STUDENT_ONLY | bəli |
| GET | `/api/exams/session/{sessionId}/result` | Nəticə (gizlətmə qaydası ilə) | STUDENT_ONLY | bəli |
| GET | `/api/exams/{id}/results` | Nəticələr siyahısı | OWNER_SCOPED | bəli |
| GET | `/api/exams/{id}/results/export` | CSV | OWNER_SCOPED | bəli |
| GET | `/api/exams/{id}/results/summary` | Nəticə xülasəsi | OWNER_SCOPED | bəli |
| GET | `/api/exams/session/{sessionId}/review` | Sessiyanın yoxlanışı (açar daxil) | OWNER_SCOPED | bəli |
| GET | `/api/exams/{id}/audience/preview` | Saxlanmış imtahanın auditoriya önizləməsi | OWNER_SCOPED | yox (c-namizəd) |
| POST | `/api/exams/audience/preview` | Yeni imtahan üçün önizləmə | OWNER_SCOPED | bəli |
| PUT | `/api/exams/{id}/audience` | Auditoriyanı ayrıca yazır | OWNER_SCOPED | yox (c-namizəd) |
| POST | `/api/exams/{id}/publish` | Dərc (hazırlıq yoxlaması ilə) | OWNER_SCOPED | bəli |
| POST | `/api/exams/{id}/reschedule` | Yeni pəncərə | OWNER_SCOPED | bəli |
| POST | `/api/exams/{id}/archive` | Arxiv | OWNER_SCOPED | bəli |
| PATCH | `/api/exams/{id}/meta` | Ad və təsvir (cəhd olsa da) | OWNER_SCOPED | yox (a) |
| GET | `/api/exams/{id}/history` | Tarixçə | OWNER_SCOPED | yox (a) |
| GET | `/api/exams/{id}/history/{revisionId}` | Bir revizyon | OWNER_SCOPED | yox (a) |
| GET | `/api/exams/{id}/versions` | Dərc versiyaları | OWNER_SCOPED | yox (a) |
| GET | `/api/exams/{id}/versions/{versionNo}` | Bir versiya (SHA-256 ilə) | OWNER_SCOPED | yox (a) |
| GET | `/api/exams/{examId}/grading` | Yoxlama növbəsi | OWNER_SCOPED | bəli |
| GET | `/api/exams/session/{sessionId}/grading` | Sessiyanın yoxlanması | OWNER_SCOPED | bəli |
| PUT | `/api/exams/session/{sessionId}/grades` | Bal qoyur | OWNER_SCOPED | bəli |
| GET | `/api/grading/pending-exams` | Yoxlama gözləyən imtahanlar | OWNER_SCOPED | bəli |
| GET | `/api/grading/pending-count` | Menyu nişanı üçün say | OWNER_SCOPED | bəli |
| POST | `/api/admin/groups` | Qrup yaradır | OWNER_SCOPED | bəli (yalnız admin menyusu) |
| GET | `/api/admin/groups` | Qruplar | OWNER_SCOPED | bəli (admin) |
| GET | `/api/admin/groups/{id}` | Qrup | OWNER_SCOPED | bəli (admin) |
| PUT | `/api/admin/groups/{id}` | Qrupu dəyişir | OWNER_SCOPED | bəli (admin) |
| DELETE | `/api/admin/groups/{id}` | Qrupu silir | OWNER_SCOPED | bəli (admin) |
| GET | `/api/admin/groups/{id}/members` | Üzvlər | OWNER_SCOPED | bəli (admin) |
| GET | `/api/admin/groups/{id}/exams` | Qrupun imtahanları | OWNER_SCOPED | bəli (admin) |
| POST | `/api/admin/groups/{id}/members` | Üzv əlavə (id, email, kod) | OWNER_SCOPED | bəli (admin) |
| POST | `/api/admin/groups/{id}/members/import` | CSV idxalı | OWNER_SCOPED | bəli (admin) |
| GET | `/api/admin/groups/{id}/invites` | Gözləyən dəvətlər | OWNER_SCOPED | bəli (admin) |
| DELETE | `/api/admin/groups/{id}/invites/{inviteId}` | Dəvəti ləğv edir | OWNER_SCOPED | bəli (admin) |
| DELETE | `/api/admin/groups/{id}/members/{userId}` | Üzvü çıxarır | OWNER_SCOPED | bəli (admin) |
| POST | `/api/groups/{id}/members/bulk-by-filter` | Filtrlə kütləvi əlavə (müəllimə 403) | OWNER_SCOPED | bəli (admin) |
| POST | `/api/groups/{id}/members/bulk-by-ids` | Id-lərlə kütləvi əlavə | OWNER_SCOPED | bəli (admin) |
| GET | `/api/groups` | Qrup adları (auditoriya seçimi) | OWNER_SCOPED | bəli |
| GET | `/api/groups/{id}/join-link` | Qoşulma linki | OWNER_SCOPED | bəli (admin) |
| POST | `/api/groups/{id}/join-link` | Linki yaradır və ya dəyişir | OWNER_SCOPED | bəli (admin) |
| PATCH | `/api/groups/{id}/join-link` | Linki açır, bağlayır | OWNER_SCOPED | bəli (admin) |
| GET | `/api/groups/{id}/join-requests` | Qoşulma sorğuları | OWNER_SCOPED | bəli (admin) |
| POST | `/api/groups/{id}/join-requests/approve` | Toplu təsdiq | OWNER_SCOPED | bəli (admin) |
| POST | `/api/groups/{id}/join-requests/reject` | Toplu rədd | OWNER_SCOPED | bəli (admin) |
| POST | `/api/groups/{id}/join-requests/{requestId}/approve` | Tək təsdiq | OWNER_SCOPED | yox (c-namizəd) |
| POST | `/api/groups/{id}/join-requests/{requestId}/reject` | Tək rədd | OWNER_SCOPED | yox (c-namizəd) |
| GET | `/api/admin/instructor-limits/{instructorId}` | Müəllim limitləri | ADMIN_ONLY | yox (a) |
| PUT | `/api/admin/instructor-limits/{instructorId}` | Limitləri dəyişir | ADMIN_ONLY | yox (a) |
| POST | `/internal/group-invites/apply` | Dəvətləri tətbiq edir | INTERNAL | – (b) |
| POST | `/internal/instructors/figures` | Müəllim rəqəmləri | INTERNAL | – (b) |
| GET | `/api/join/{code}` | Linkin qrup adı | PUBLIC | bəli |
| GET | `/api/join/requests/mine` | Öz qoşulma sorğularım | STUDENT_ONLY | yox (a) |
| POST | `/api/join/{code}` | Qoşulur | STUDENT_ONLY | bəli |
| GET | `/api/me/stats` | Öz rəqəmlərim və başlanğıc bayraqları | OWN_DATA | bəli (bayraqlar göstərilmir) |
| GET | `/api/me/profile` | Öz profilim | OWN_DATA | bəli |
| PUT | `/api/me/profile` | Profili yazır | OWN_DATA | bəli |
| GET | `/api/me/avatar` | Profil şəkli | OWN_DATA | yox (a, filial birləşməyib) |
| POST | `/api/me/avatar/upload-url` | Şəkil yükləmə linki | OWN_DATA | yox (a) |
| POST | `/api/me/avatar/confirm` | Yükləməni təsdiq edir | OWN_DATA | yox (a) |
| DELETE | `/api/me/avatar` | Şəkli silir | OWN_DATA | yox (a) |
| POST | `/api/media/upload-url` | Media yükləmə linki | OWNER_SCOPED | bəli |
| POST | `/api/media/{id}/confirm` | Yükləməni təsdiq edir | OWNER_SCOPED | bəli |
| GET | `/api/media/{id}/preview-url` | Önizləmə linki | OWNER_SCOPED | bəli |
| DELETE | `/api/media/{id}` | Medianı silir | OWNER_SCOPED | bəli |
| GET | `/api/exams/monitoring/active` | Gedən imtahanlar | OWNER_SCOPED | bəli |
| GET | `/api/exams/{examId}/monitoring` | Canlı izləmə | OWNER_SCOPED | bəli |
| GET | `/api/exams/session/{sessionId}/violations` | Sessiyanın pozuntuları | OWNER_SCOPED | bəli |
| GET | `/api/admin/participants` | İştirakçı siyahısı (müəllimə öz üzvləri) | OWNER_SCOPED | bəli (yalnız admin menyusu) |
| GET | `/api/admin/participants/summary` | Xülasə | ADMIN_ONLY | bəli |
| PATCH | `/api/admin/participants/{userId}/profile` | Profili admin dəyişir | ADMIN_ONLY | bəli |
| GET | `/api/admin/participants/{userId}` | İştirakçı kartı | OWNER_SCOPED | bəli (müəllimə də açıq) |
| GET | `/api/admin/participants/{userId}/sessions` | Kartdakı cəhdlər | OWNER_SCOPED | bəli (müəllimə də açıq) |

### Nə hazırdır, nə yarımçıqdır

- Hazır: hər endpoint-in sinfi var və test ilə qorunur; yeni endpoint sətirsiz əlavə oluna bilməz.
- Yarımçıq: B13 commit mesajı auth-service üçün "31 endpoint" yazır, amma reyestrdə həmişə 29 sətir olub
  (`git show f55d383:auth-service/src/test/resources/endpoint-access.txt`). BACKLOG-da `d9dd6d9` ilə 29-a düzəldildi.

---

## 4. d) Rollar və icazələr

### Nə var

Rollar: `ADMIN`, `INSTRUCTOR` (müəllim), `STUDENT` (iştirakçı). Enum-da `MODERATOR` da var
(`CM/domain/entity/UserRole.java`), amma kodda heç yerdə istifadə olunmur. TEACHER rolu yoxdur.

Canlı bazada (2026-10-21): 1 ADMIN, 21 INSTRUCTOR, 1050 STUDENT.

| Kim | Nə görür və edir |
|---|---|
| **Admin** | Hər şeyi: bütün imtahanlar, qruplar, iştirakçılar, nəticələr, audit; PUBLIC imtahan yalnız o edə bilər; müəllim hesabı yaradır, limit qoyur; filtrlə kütləvi əlavə yalnız onundur |
| **Müəllim** | Yalnız özünün: öz imtahanları, sessiyaları, nəticələri, yoxlaması, monitorinqi, analitikası, tarixçəsi, qrupları, üzvləri, dəvətləri, linkləri, mediası; iştirakçılardan yalnız öz qruplarının üzvlərini; auditdən yalnız öz hərəkətlərini. İmtahanı yalnız GROUP ola bilər |
| **İştirakçı** | PUBLIC imtahanlar və üzv olduğu qrupların imtahanları; öz sessiyaları, cavabları, nəticəsi; linklə qrupa qoşulma; öz profili |

**Sahiblik qaydası (B13).** Bir yer qərar verir: `ES/service/OwnershipPolicy.java`. Admin üçün filtr yoxdur,
müəllim üçün öz id-si, başqası üçün heç kimin olmayan "nil UUID". Filtr həmişə SQL-də tətbiq olunur. İmtahan üçün
`ES/service/ExamAccessPolicy.java`, qrup üçün `ES/service/GroupAccessPolicy.java` eyni qaydanı işlədir.

**404 və 403.**
- Başqa müəllimin imtahanı, sessiyası, qrupu yoxdur kimi cavab alır: eyni 404 və eyni kod
  (`EXAM_NOT_FOUND`, `SESSION_NOT_FOUND`, `GROUP_NOT_FOUND`). Heç nə yazılmır. Köhnə `EXAM_NOT_OWNER` (403) B13-də silinib.
- 403 yalnız rolun endpoint-ə ümumiyyətlə haqqı olmayanda qaytarılır (məsələn, iştirakçı `/api/admin/groups`).
- Müəllim PUBLIC istəsə 403 `PUBLIC_EXAM_ADMIN_ONLY`.

### Texniki qeyd

- Gateway yalnız tokenin olub-olmadığını yoxlayır (`GW/config/SecurityConfig.java`); rolu servis özü yoxlayır
  (path qaydası və `@PreAuthorize`).
- Müəllim hesabını yalnız admin yaradır (`AS/service/InstructorAdminService.java`). Qeydiyyat həmişə STUDENT edir
  (`AS/service/AuthService.java`, test `RegisterRoleTest`).
- `TeacherIsolationMatrixTest` iki müəllimin bir-birinin hər resursuna toxunmasını real bazada yoxlayır.

### Nə hazırdır, nə yarımçıqdır

- Hazır (kodda): sahiblik qaydası, vahid 404, müəllim limitləri.
- Yarımçıq: B13 hələ `main`-də deyil; frontend müəllimə qrup, iştirakçı və audit ekranlarını göstərmir (bölmə 13);
  B13-dən əvvəl müəllimlərin PUBLIC etdiyi imtahanlar PUBLIC qalıb (commit `ad8016f`); 17 köhnə müəllim
  (`@example.com`) müvəqqəti parolsuzdur (BACKLOG, B13).

---

## 5. e) İmtahan modeli və həyat dövrü

### Nə var

**Quruluş.** İmtahan bloklardan ibarətdir (ən azı biri, "Əsas blok"), blokda suallar, sualda variantlar və media.
Sual növləri: `MULTIPLE_CHOICE` (tək və çox seçimli), `TRUE_FALSE`, `FILL_BLANK`, `OPEN_ENDED`
(`CM/domain/entity/QuestionType.java`). `feat/question-types`-da əlavə olaraq: `ORDERING` (Sıralama: bəndlərin düzgün
sırası), `MATCHING` (Uyğunlaşdırma: sol və sağ tərəf, V21), `SHORT_ANSWER` (Qısa cavab, FILL_BLANK kimi), `LINEAR_SCALE`
(Diapazon: 0 və ya 1-dən 2..10-a qədər bir rəqəm, kənar yazıları ilə, V22; düzgün rəqəm sualın yeganə variantıdır).
Sessiya sıralama bəndlərini və uyğunlaşdırmanın sağ tərəflərini yalnız həmin sessiyanın id-ləri ilə alır (`AnswerTokens`).
Hamısı avtomatik və "hamısı və ya heç nə" qaydası ilə qiymətləndirilir. Qaydası: PROJECT_RULES, "Question types with a
structured answer".

**Vaxt rejimi.** `EXAM_TIMER` (bütün imtahana bir taymer) və `BLOCK_TIMERS` (hər blokun öz vaxtı, aralarında fasilə).

**İştirakçı tərəfində bloklar (B12b).** Qaydalar yalnız bloklu imtahanlara aiddir: birdən çox blok, blok taymerləri və ya
"yalnız irəli" blok. Bir bloklu, ümumi taymerli, sərbəst naviqasiyalı imtahan (B12a-dan əvvəlki hər imtahan) əvvəlki kimi
işləyir: sətir yazılmır, yoxlama yoxdur.
- Ardıcıl rejim (blok taymerləri, və ya ümumi taymer + "geri qayıtmaq olmaz"): bir anda bir blok açıqdır. Blok iştirakçı
  onu bitirəndə və ya vaxtı bitəndə bağlanır və bir daha açılmır. Blok taymerlərində sonra fasilə gələ bilər; növbəti blok
  fasilə bitəndə özü başlayır, imtahan icazə verirsə iştirakçı fasiləni erkən bitirə bilər.
- Açıq rejim (ümumi taymer + "geri qayıtmaq olar"): bütün bloklar sessiya bitənə qədər açıqdır.
- "Yalnız irəli" blok: keçilən suala qayıtmaq olmur (`cursor`).
- Vaxt xətti yalnız vaxtlardan hesablanır (`ES/session/BlockTimeline.java`): iştirakçı baxmasa da blok öz vaxtında
  bitir, fasilə öz vaxtında bitir. Yalnız iştirakçının seçdiyi anlar yazılır. Hər addım sessiya sətrini kilidləyib
  `session_block_progress`-ə yazılır (`ES/session/SessionBlockService.java`); sessiyanın `end_time`-ı vaxt xəttinə uyğun
  yalnız irəli çəkilir, ona görə scheduler sessiyanı əsl sonunda bitirir.
- Sessiya bağlı blokun sualını görmür (`/questions` və `/blocks` süzür); cavab isə yoxlanılır: 409 `BLOCK_CLOSED`,
  `SESSION_ON_BREAK`, `QUESTION_LOCKED`; son blok bitibsə sessiya tamamlanır və 410 `SESSION_EXPIRED`.
- Sualların sırası sessiyaya görə toxumla qarışdırılır (`ES/session/SessionOrder.java`): eyni sessiya hər dəfə eyni sıranı
  görür, sual blokundan çıxmır.
- Endpoint-lər: `GET /api/exams/session/{id}/blocks`, `POST .../blocks/finish`, `POST .../blocks/end-break`,
  `POST .../blocks/reach/{questionId}`; start cavabında `blocks`.
- Açar: `EXAM_BLOCK_RULES_ENABLED` (default `true`); `false` olsa belə imtahanlar dərc olunmur (409 `FEATURE_NOT_AVAILABLE`).
- Hazırlıq xəbərdarlıqları: `BREAK_IGNORED` (ümumi taymerdə fasilə), `RETURN_IGNORED` (blok taymerlərində geri qayıtma).

**Revizyon tarixçəsi.** İmtahanın hər dəyişikliyi `exam_revisions`-a bir sətir yazır, dəyişikliyin öz
tranzaksiyasında. Sətirlər dəyişdirilə və silinə bilməz (trigger). İmtahan silinsə də tarixçə qalır. Cavab açarı
və bal dəyişikliyi "sensitive" işarələnir. Dəyişiklik səbəbi (`changeReason`) default olaraq məcburi deyil
(`REQUIRE_CHANGE_REASON_AFTER_PUBLISH=false`). Kod: `ES/history/`.

**Snapshot.** Hər dərcdə imtahanın tam surəti (ayarlar, auditoriya, bloklar, suallar, açar, media id-ləri)
kanonik JSON kimi `exam_published_snapshots`-a yazılır, yanında SHA-256. Versiya nömrəsi hər dərcdə artır. Hash-i
yenidən hesablayıb yoxlamaq olur (`ES/history/ExamSnapshotService.java`).

**Klon.** `POST /api/exams/{id}/clone` yeni QARALAMA yaradır, adı "<ad> (nüsxə)". Bloklar, suallar, açar, ayarlar,
media kopyalanır; auditoriya, sessiyalar, nəticələr, tarixçə kopyalanmır. Müəllimin PUBLIC imtahan nüsxəsi GROUP olur.

**Hazırlıq yoxlaması.** `GET /api/exams/{id}/readiness` və dərc eyni yoxlamanı işlədir
(`ES/service/ExamReadinessService.java`). Dərci bloklayanlar: `NO_QUESTIONS`, `EMPTY_BLOCK`,
`MULTIPLE_CHOICE_INCOMPLETE`, `NO_CORRECT_ANSWER`, `TRUE_FALSE_INCOMPLETE`, `FILL_BLANK_INCOMPLETE`, `ZERO_POINTS`,
`MEDIA_MISSING`, `BLOCK_TIME_LIMIT_MISSING`, `DURATION_EXCEEDS_WINDOW`, `WINDOW_ENDED`, `FEATURE_NOT_AVAILABLE`.
Xəbərdarlıqlar: `AUDIENCE_EMPTY`, `START_IN_PAST`, `BLOCK_TIME_LIMIT_IGNORED`. Uğursuz dərc 409 `EXAM_NOT_READY`.

**Giriş növləri.** `PUBLIC`: hər daxil olmuş istifadəçi (B13-dən sonra yalnız admin qoya bilər). `GROUP`: auditoriya
qruplarından ən azı birinin üzvü (50 qrupa qədər, birləşmə). Qrupdan çıxan iştirakçı yeni cəhd başlada bilmir, amma
başladığını bitirə bilər.

**Qruplar və qoşulma linkləri.** Qrupun sahibi var (`owner_id`, V12). Ad sahib daxilində unikaldır (V18). Üzv
əlavə etmək: tək-tək (id, email, 7 rəqəmli kod), CSV, filtrlə (yalnız admin), id siyahısı ilə (20 000-ə qədər, əvvəl
"quru qaçış"). Hər qrupun bir linki var: 10 simvollu kod (0, O, 1, I yoxdur), istəyə görə təsdiq, istifadə limiti,
bitmə vaxtı. Rədd olunan iştirakçı 24 saat sonra yenidən istəyə bilər (`GROUP_JOIN_REJECT_COOLDOWN_HOURS`).
Kod: `ES/service/GroupJoinService.java`, `ES/service/GroupBulkService.java`.

**7 rəqəmli kod.** Hər hesabın `participant_code`-u var (1000000–9999999). Onu baza təyin edir (ardıcıllığın
qarışdırılmış forması, auth V3), dəyişmək olmur (trigger, auth V5), unikaldır. Yalnız göstərmək və axtarmaq üçündür.
Yoxlama: `scripts\check-participant-codes.ps1`.

### Həyat dövrü

```
yaradılma (DRAFT) ──publish (hazırlıq yoxlaması)──► ACTIVE ──end_time (scheduler)──► COMPLETED ──archive──► ARCHIVED
                                                       ▲                                   │
                                   reschedule (start irəlidədirsə SCHEDULED, scheduler ACTIVE edir) ◄──┘
DELETE: yalnız heç kim başlamayıbsa; sessiya varsa 409 EXAM_CANNOT_DELETE (arxivləmək lazımdır).
CANCELLED statusu var, amma heç bir endpoint onu qoymur.
```

1. **Yaradılma.** Admin və ya müəllim imtahanı qaralama kimi yaradır (`POST /api/exams`, başlanğıc gələcəkdə olmalıdır).
2. **Aktivləşmə.** Dərc hazırlıq yoxlamasından keçir, snapshot və tarixçə sətri yazılır. Başlanğıc irəlidə olsa da
   ACTIVE olur, amma vaxtından əvvəl başlamaq olmur (BACKLOG).
3. **İştirak.** `start` cəhd limitini SQL-də, bir kilid altında yoxlayır (ümumi və gündəlik, gün Bakı vaxtı ilə).
   Cavablar saxlanılır, iştirakçı onları geri oxuya bilir. Vaxt bitəndə scheduler avtomatik təhvil verir.
4. **Nəticə.** Avtomatik suallar dərhal qiymətlənir. OPEN_ENDED əl ilə yoxlanılır (`PUT .../grades`, versiya
   qoruması ilə). Balı görmək qaydası bir metoddadır (`AnswerSubmissionService.resultsWithheld`).
5. **Analitika.** Sual analizi (çətinlik, ayırdetmə), imtahan icmalı, platforma icmalı, canlı monitorinq.
   Hamısı yalnız oxuyur, heç nəyi dəyişmir.

### Nə hazırdır, nə yarımçıqdır

- Hazır: bloklar, tarixçə, snapshot, klon, hazırlıq, qruplar, linklər, kod, cəhd limitləri, əl ilə yoxlama, analitika.
- Hazır (2026-10-22): iştirakçı tərəfində blok qaydaları (B12b) və frontend-də blok redaktoru ilə imtahan ekranı; yeni sual növləri `main`-də və canlıdadır.
- Yarımçıq: blokların vaxt xəttini admin monitorinqdə görmür; sual bankı, regrade və vaxt uzatma yoxdur (AUDIT_GAP).

---

## 6. f) Təhlükəsizlik

### Nə var

| Mövzu | Necə işləyir | Harada |
|---|---|---|
| Qeydiyyat | Həmişə STUDENT, token vermir; email kodu təsdiqlənəndən sonra token | `AS/service/AuthService.java` |
| OTP | 6 rəqəm, `SecureRandom`, bazada yalnız HMAC-SHA256 (`OTP_HMAC_SECRET`), bir dəfəlik, limitlər SQL-də | `AS/service/OtpService.java` |
| Giriş | Səhv parol 401, təsdiqlənməmiş email 403 `EMAIL_NOT_VERIFIED`, çox səhv cəhd kilid (`ACCOUNT_LOCKED`) | `AS/service/AuthService.java` |
| JWT | Access token 15 dəq, refresh token 7 gün; hər servis və gateway eyni filtri işlədir | `CM/security/JwtTokenProvider.java`, `CM/security/JwtAuthenticationFilter.java` |
| Tokenin dərhal bitməsi (B13) | Söndürmə, admin parol sıfırlaması, parol dəyişikliyi Redis-də `auth:tokens-valid-after:<user>` yazır; köhnə tokenlər 401. Redis 250 ms-də cavab verməsə token buraxılır (platforma dayanmasın deyə) | `CM/security/TokenRevocationStore.java` |
| Müvəqqəti parol (B13) | Admin müəllim yaradanda 16 simvollu parol bir dəfə göstərilir, 7 gün işləyir (sonra 401 `TEMPORARY_PASSWORD_EXPIRED`). Tokendə `pwc` iddiası olur: yalnız `POST /auth/change-password` və `POST /auth/logout`, qalan hər şey 403 `PASSWORD_CHANGE_REQUIRED`. Bazada `must_change_password` | `AS/service/TemporaryPasswords.java`, auth V8 |
| Rate limit (gateway) | Marşrut və müştəri ünvanı üzrə Redis bucket. Qeydiyyat və giriş: development 100/200, ictimai rejim 500/1000; OTP marşrutları 1 token/s, 50 tutum, sorğu 5 token; parol dəyişmə 1/25; müəllim idarəsi 2/60; ümumi auth 100/200; exam 50/100 | `api-gateway/src/main/resources/application.yml`, `GW/config/RateLimiterConfig.java` |
| Rate limit (exam-service) | Bucket4j, ünvan üzrə; gateway arxasında hamı bir ünvandan gəlir (BACKLOG) | `ES/config/RateLimitConfig.java` |
| Müştəri ünvanı | `X-Forwarded-For` yalnız `GATEWAY_TRUSTED_PROXIES`-dən | `GW/config/ClientIpResolver.java` |
| Audit | exam-service `group_audit_log`, auth-service `admin_audit_log`; `GET /api/admin/audit` ikisini birləşdirir. Kod, OTP, parol, tam email yazılmır; `AuditService.clean` son müdafiədir | `ES/service/AuditService.java`, `AS/service/AccountAuditLog.java` |
| Daxili endpoint-lər | `/internal/**` gateway-dən keçmir, `X-Internal-Secret` (`INTERNAL_SERVICE_SECRET`) olmadan 404 | `CM/security/InternalServiceAuth.java` |
| CORS | `CORS_ALLOWED_ORIGINS`; ictimai rejimdə yalnız `http://127.0.0.1:8088` (edge brauzerin Origin-ini özü idarə edir) | gateway `application.yml`, `docker-compose.public.yml` |
| Gizli açarlar | `.env` (git-ignore, commit olunmur); ictimai rejim üçün `scripts\init-public-env.ps1` yaradır, heç vaxt çap etmir; `DB_PASSWORD` yalnız o skriptlə dəyişir | `.env.example`, `scripts\init-public-env.ps1` |
| Media | Bucket şəxsidir, yalnız imzalı link; fayl serverdən keçmir; `confirm` ölçünü və "magic bytes"-ı yoxlayır | `ES/storage/MediaStorage.java`, `ES/service/MediaTypePolicy.java` |

### Gizlilik: şəxsi məlumatı kim görür

| Məlumat | Admin | Müəllim | İştirakçı |
|---|---|---|---|
| Tam email | İştirakçı siyahısında, nəticələrdə, yoxlamada | Nəticələrdə, yoxlamada və iştirakçı kartında öz qruplarının və imtahanlarının iştirakçıları üçün; qrup üzvləri siyahısında maskalı | Yalnız özününkü |
| Ad, soyad, kod | Hamının | Öz qruplarının üzvləri; kodla əlavədə maskalı ad | Özününkü |
| Profil (müəssisə, doğum tarixi və s.), telefon, kilid vəziyyəti | Kartda | Yox: kartdan serverdə çıxarılır (`ES/service/ParticipantCardService.java`); kartda yalnız öz qrupları, öz imtahanlarındakı cəhdlər, hadisəsiz | Özününkü |
| Cavab açarı | Bəli | Öz imtahanları | Heç vaxt |
| Fırıldaq hadisələri | Bəli | Öz imtahanları | Yox |
| Audit | Hamısı | Yalnız öz hərəkətləri, email-lər əlavə maskalanır | Yox |

Emailin nəticələrdə açıq, üzv siyahısında maskalı olması ziddiyyətdir; BACKLOG-da açıq qərar kimi durur.

### Nə hazırdır, nə yarımçıqdır

- Hazır: OTP, kilid, JWT, tokenin dərhal bitməsi, müvəqqəti parol, rate limit, audit, daxili gizli başlıq, CORS.
- Yarımçıq: CAPTCHA yoxdur; 2FA yoxdur; `INTERNAL_SERVICE_SECRET` dövri dəyişmir; development portları LAN-a açıqdır;
  Redis development-də parolsuzdur; gizlilik siyasəti və istifadə şərtləri səhifəsi yoxdur (frontend `FE/pages/Home.tsx`
  sətir 219-da açıq yazılıb); məlumatın saxlama müddəti yalnız OTP (7 gün) və təsdiqlənməmiş hesab (14 gün) üçün var,
  nəticələr, audit, sessiyalar üçün yoxdur.

---

## 7. g) Keş və performans

### Nə var

- **ReadCache** (`ES/service/ReadCache.java`): exam-service-in yeganə Redis keşi. Monitorinq 3 s, analitika 60 s,
  yoxlama icmalı 10 s. Heç nə silinmir, dəyər ən çox TTL qədər köhnə olur. Redis 250 ms-də cavab verməsə bazadan
  oxunur. İştirakçının yolunda (start, cavab, təhvil) işlədilmir.
- **ExamCacheService** (`ES/service/ExamCacheService.java`): Redis deyil, yaddaşdakı xəritədir (imtahanlar və
  sessiyanın sualları). Bir neçə exam-service nüsxəsində fərqli olar; yenidən başlayanda sualların sırası yenidən qarışar.
- **Tranzaksiyalar.** `@Transactional` bu servislərdə işləmir. Atomik yazılar `TransactionalOperator` ilə
  (18 faylda). Kodda 10 yerdə `@Transactional` sözü var, hamısı izah şərhidir.
- **Ölçülmüş performans** (testlər, 2026-10-21): 20 000 üzvlü müəllimin siyahısı 300 ms-dən az
  (`TeacherScopePerformanceTest`, `TeacherParticipantsPerformanceTest`), iştirakçı kartı və sıralama
  (`ParticipantCardPerformanceTest`, `ParticipantSortPerformanceTest`), tarixçə (`ExamHistoryPerformanceTest`).

### Bilinən yavaş yerlər (BACKLOG-dan, koda qarşı yoxlanmadı)

- İdarə siyahısında ad axtarışı `ILIKE '%mətn%'`: indeks işləmir, minlərlə imtahanda trigram indeks lazımdır.
- `/api/exams/available` hər imtahan üçün iki sessiya sorğusu (N+1, səhifədə 50-yə qədər).
- Ada görə sıralanan nəticələr serverdə sıralanır: bütün sətirlər oxunur, 10 000-dən çox olsa rədd olunur.
- Audit siyahısı iki bazanı birləşdirir və yalnız 10 000 sətir dərinliyə səhifələnir.
- CSV eksportu 500 sətirdən bir auth-service-ə müraciət edir.
- auth-service əlçatmaz olanda adlı siyahılar 2 s gözləyir.

### Texniki qeyd

- Bazaya bağlantı hovuzları: development exam 10–50; production profili exam 30–150, auth 20–100; ictimai rejim
  bunları 50 + 25 + 10-a endirir.
- Sorğu bədəni 256 KB ilə məhdudlaşır (413 `PAYLOAD_TOO_LARGE`).

---

## 8. h) Skriptlər

Hamısı `scripts\` qovluğundadır. Heç biri `docker compose down -v` işlətmir.

| Skript | Nə edir | Nə vaxt | Təhlükəsi |
|---|---|---|---|
| `backup.ps1` | pg_dump (3 baza) və media bucket-i `backups\<tarix>\`-ə, SHA256SUMS ilə; son 14-ü saxlayır | Hər riskli addımdan əvvəl və sonra; `go-public.ps1 start` özü çağırır | Az. Backup eyni diskdə qalır; şifrələnmir |
| `restore-test.ps1` | Son backup-ı müvəqqəti bazalara bərpa edib sətir saylarını müqayisə edir | Backup-dan sonra | Az (yalnız `restoretest_` bazalarına yazır). Yalnız sayları müqayisə edir, məzmunu yox |
| `cleanup-test-data.ps1` | `@x.test` hesabları və `T-` imtahan və qrupları silir; default quru qaçış; `-Execute` əl ilə yazılan təsdiq sözü istəyir və əvvəl backup alır | İctimai rejimi açmadan əvvəl | Orta: canlı bazada silir. Admin və qorunan email-ləri saxlayır. Media faylları silinmir, siyahısı yazılır |
| `go-public.ps1` | İctimai rejim: `start`, `stop`, `status`, `url` | Saytı açmaq, bağlamaq | Orta: real istifadəçilərə açır; `start -SkipBackup` backup-sız işləyir |
| `watch-public.ps1` | Sayt düşəndə yeni tunellə qaldırır | Daim (Task Scheduler) | Az; tapşırıq hazırda söndürülüb |
| `deploy-frontend.ps1` | Yalnız frontendi dəyişir, ünvan qalır; uğursuzluqda geri qaytarır | Yalnız frontend dəyişəndə | Az |
| `build-frontend.ps1` | Frontendi `deploy\public\site`-a build edir | `go-public` və `deploy-frontend` çağırır | Az |
| `init-public-env.ps1` | `.env`-də zəif və ya olmayan sirləri yaradır, `DB_PASSWORD`-u `ALTER ROLE` ilə tətbiq edir | İctimai rejimdən əvvəl bir dəfə | Orta: bazanın parolunu dəyişir |
| `up.ps1` | `.env`-i yoxlayıb development stack-i qaldırır (`up -d`) | Development | Az |
| `check-env.ps1` | `.env` compose-un tələb etdiyi açarları saxlayırmı | `up.ps1`, `go-public.ps1` çağırır | Yox (yalnız oxuyur) |
| `check-gateway-routes.ps1` | İki gateway profilinin marşrutları eynidirmi | Gateway dəyişəndən sonra | Yox |
| `check-gateway-production.ps1` | Production profilli müvəqqəti gateway-dən hər frontend yoluna sorğu | Gateway dəyişəndən sonra | Az (müvəqqəti konteyner) |
| `check-participant-codes.ps1` | İştirakçı kodlarının bazadakı qaydalarını yoxlayır | Kodla bağlı dəyişiklikdən sonra | Yox |
| `send-test-mail.ps1` | Test məktubu (OTP və ya parol bərpası) | Mail ayarından sonra | Az: real məktub gedə bilər |
| `check-project-map.ps1` | Kod dəyişib, bu sənəd dəyişməyibsə xəbərdarlıq edir (bölmə 18) | Hər commit-dən əvvəl | Yox (yalnız git-i oxuyur) |
| `regression\verify-b*.ps1` (14 skript) | Köhnə iş paketlərini canlı development stack-ində yoxlayır | Böyük dəyişiklikdən sonra, **ictimai rejim bağlı olanda** | Yüksək: test məlumatı yaradır və canlı sayt eyni bazanı işlədir. b11, b11b, b12a, b13 ictimai rejimdə işləməkdən imtina edir |

Regression skriptləri: verify-b2, b3, b4, b5-join, b6-buckets, b6-parallel, b7, b8, b9, b10, b11, b11b, b12a, b13 və
ortaq `_common.ps1`. `verify-b13.ps1` README-yə `6acab43` ilə əlavə olundu (`feat/b13-teacher-isolation`).

---

## 9. i) Testlər

### 2026-10-21 nəticəsi

Komanda: `.\mvnw.cmd -B clean test` (`docs/project-map` worktree-də, `cb19e65` kodu, JDK 21). Testcontainers öz
müvəqqəti PostgreSQL-ini (`postgres:16-alpine`) qaldırır; canlı bazaya toxunulmur. Müddət 4 dəq 39 san.

| Modul | Test sinfi | Test | Uğursuz | Xəta | Atlanan |
|---|---|---|---|---|---|
| common | 4 | 14 | 0 | 0 | 0 |
| auth-service | 19 | 65 | 0 | 0 | 0 |
| exam-service | 39 | 145 | 0 | 0 | 0 |
| api-gateway, monitoring, websocket | 0 | 0 | – | – | – |
| **Cəmi** | **62** | **224** | **0** | **0** | **0** |

`feat/question-types` (`b756907`, 2026-10-22): 237 test, hamısı keçir (common 14, auth 65, exam 158; `LinearScaleTest` 7,
`QuestionTypesTest` 6). Frontend `feat/question-types` (`0d59528`): 618 test keçir, 13 atlanır (əvvəl 605); tsc, lint, build təmiz.

- `src/test`-də 63 Java faylı var: 62 test sinfi və köməkçi `ExamTestStack`.
- 32 fayl Testcontainers işlədir (31 test sinfi və `ExamTestStack`).
- `BackupMigrationTest` 0 test göstərir: yalnız `-Db13.backup=<backup qovluğu>` verildikdə işləyir (`assumeTrue`).
  Bu dəfə verilmədi, çünki o, real backup-ı açır.
- Docker olmayanda Testcontainers testləri `assumeTrue` ilə keçilir (atlanmış sayılmır, sadəcə işləmir).
- Gateway-də heç bir test yoxdur.
- Regression skriptləri bu dəfə işlədilmədi: ictimai rejim açıq idi və onlar canlı bazaya test məlumatı yazır.

---

## 10. j) Git

| Filial | Vəziyyət | Son commit | Qeyd |
|---|---|---|---|
| `main` | | `45138df`, 2026-10-19 19:36 | B11b, B12a tətbiq olunub |
| `feat/b13-teacher-isolation` | birləşməyib, `main`-dən 21 commit irəli | `6acab43`, 2026-10-21 | B13; son iki commit B13 sənədləri (`d9dd6d9`) və regression skriptləri (`6acab43`) |
| `feat/avatar` | birləşməyib, `94e2511` üstündə 1 commit (B13-ün son iki commit-i onda yoxdur) | `cb19e65`, 2026-10-20 10:38 | profil şəkli, exam V20 |
| `feat/question-types` | `feat/avatar` üstündə; `feat/b13-teacher-isolation` ona birləşdirilib | `b756907`, 2026-10-22 | dörd yeni sual növü, V21, V22; 237 test keçir |
| `docs/project-map` | bu sənəd, `cb19e65` üstündə | | `feat/avatar`-dan sonra birləşdirilməlidir |

Birləşmə sırası `main` ← `feat/b13-teacher-isolation` ← `feat/avatar` ← `docs/project-map` 2026-10-21-də
`git merge-tree --write-tree` ilə sınandı: hər üç addım konfliktsizdir.
| Qalan 39 filial | `main`-ə birləşib | | silinə bilər (sahibin qərarı) |

- Uzaq repo: `origin` (GitHub). Push edilmir (sahibin qaydası).
- Son commit-lər: `git log --oneline -15 main` və `git log --oneline main..feat/avatar`.

---

## 11. k) Mövcud sənədlər: istinadlar və ziddiyyətlər

| Sənəd | Nə üçündür | Son dəyişiklik | Vəziyyət |
|---|---|---|---|
| [BACKLOG.md](BACKLOG.md) | Açıq işlər | 2026-10-21 (`feat/b13-teacher-isolation`) | B13 sətirləri `d9dd6d9`-dadır; bir neçə bənd köhnəlib (aşağıda) |
| [PROJECT_RULES.md](PROJECT_RULES.md) | Qaydalar | 2026-10-21 (`feat/b13-teacher-isolation`) | B13 qaydaları `d9dd6d9`-dadır; bir qayda köhnəlib |
| [AUDIT_GAP.md](AUDIT_GAP.md) | Boşluq auditi | 2026-10-15 (`main` `47c9d60`, V1–V13) | Tarixi sənəd; bəzi "YOX"-lar artıq var |
| [PUBLIC_TEMP.md](PUBLIC_TEMP.md) | İctimai rejim | | Aktualdır, bir ziddiyyət var |
| `README.md` | Ümumi təsvir | 2026-10-15 | Endpoint və sxem bölmələri köhnədir |
| `START.md`, `PERFORMANCE.md`, `AUTH_SERVICE_IMPLEMENTATION_REPORT.md` | Köhnə təlimat və hesabatlar | 2026-03 – 2026-10-12 | Tarixidir |
| `scripts/regression/README.md` | Regression skriptləri | 2026-10-21 (`6acab43`) | `verify-b13.ps1` sətri əlavə olunub |
| `AUDIT_REPORT.md` (kök qovluq) | Köhnə audit | – | Git-də yoxdur: yalnız bu kompüterin `.git/info/exclude` faylı ilə gizlədilib; PROJECT_RULES ona toxunmamağı deyir |
| `exam-system/` (kök qovluqda) | Layihənin köhnə nüsxəsi, git-də izlənir | – | PROJECT_RULES-a görə dəyişdirilmir |

### Köhnəlmiş və ya ziddiyyətli yerlər

1. **BACKLOG "Platform" və "Ops":** "No automated tests in any module" və "The backend has no automated tests at all".
   İndi 224 test var.
2. **BACKLOG "Platform":** "Groups have no owner, so every group action is ADMIN only". V12-dən bəri `owner_id` var.
3. **BACKLOG "Auth":** "No admin endpoint to change a user's role" və "no token revocation list". B13-də müəllim hesabı
   admin endpoint-i ilə yaranır və tokenlər dərhal bitə bilir (rol dəyişmə endpoint-i isə hələ də yoxdur).
4. **BACKLOG "Exam":** "Manual grading for open-ended questions (today: exact match only)" açıq görünür; əl ilə
   yoxlama hazırdır (`GradingService`, V8). "ADMIN cannot edit or delete another user's exam" da köhnədir.
5. **BACKLOG "Platform":** rədd olunan qoşulma sorğusu üçün gözləmə müddəti yoxdur deyilir; artıq var (24 saat).
6. **BACKLOG "Ops":** qrup adının qlobal unikal olması (`uq_user_groups_name`) açıq sızma kimi qalıb; V18 bunu bağlayıb.
7. **BACKLOG "Ops":** "frontend still posts the old body" (fraud event). Frontend artıq `{type, occurredAt, details}`
   göndərir; admin üçün oxuma endpoint-i isə var (`/api/exams/session/{sessionId}/violations`).
8. **BACKLOG "Exam" sətir 21:** iki bənd bir sətirə yapışıb ("No change email flow" və "Submit response ignores...").
   Bölmələr arasında 13 bənd təkrarlanır ("Exam" və "Auth").
9. **PROJECT_RULES (commit olunmuş):** "403 is only for an exam the caller can see but may not manage". B13-dən sonra
   bu da 404-dür; düzəliş `d9dd6d9`-dadır (`main`-ə birləşəndə həll olunur).
10. **PROJECT_RULES "Temporary public mode":** "Postgres 127.0.0.1:5432 only" yazır; `docker-compose.public.yml`
    Postgres-i ümumiyyətlə açmır (`ports: !reset []`).
11. **B13 commit mesajı:** auth-service üçün "31 endpoint". Real rəqəm 29-dur (BACKLOG-da `d9dd6d9` ilə düzəldildi).
12. **README:** endpoint-lər `/api/v1/auth`, `/api/v1/exams` kimi göstərilir, qeydiyyat nümunəsində `username` və
    `role` var. Real yollar `/auth/*`, `/api/exams/*`; qeydiyyat rol qəbul etmir. Sxem diaqramı köhnədir (BACKLOG da qeyd edir).
13. **PUBLIC_TEMP "Sayt düşəndə":** tapşırığı `-Once` olmadan, girişdə bir dəfə qurmağı təklif edir. Real tapşırıq
    `-Once` ilə hər 5 dəqiqədən bir işləyir və söndürülüb.
14. **PUBLIC_TEMP "Gecə backup":** tapşırıq təsvir olunub, amma Windows-da "ExamSystem nightly backup" tapşırığı yoxdur.
15. **AUDIT_GAP §1:** "Profil səhifəsi mövcud olmayan endpointlərə müraciət edir", "INSTRUCTOR rolu yalnız SQL ilə
    verilir", "İmtahanı kopyalamaq YOX", "Sual versiyalama YOX". B9, B12a, B13-dən sonra bunlar var.

---

## 12. l) Riskli yerlər

1. **Canlı sayt və development eyni bazadır.** İctimai rejim development compose-un üstündə işləyir, eyni
   `exam-system_postgres_data` həcmini götürür. Development-də edilən hər test, hər skript canlı istifadəçilərin
   məlumatına yazır. Ayrıca staging bazası yoxdur.
2. **Test məlumatı canlıdadır.** 2026-10-21: 1072 hesabdan 996-sı `@x.test`, 209 imtahandan 153-ü `T-`, 80 qrupdan 53-ü
   `T-`. ACTIVE + PUBLIC 16 imtahanın **hamısı** `T-` test imtahanıdır, yəni ictimai saytın iştirakçıları yalnız test
   imtahanları görür. BACKLOG bunu ictimai rejimdən əvvəl təmizləməyi tələb edirdi; sayt indi açıqdır.
3. **Backup və bərpa.** 14 backup var (son: `backups\20261021-1520`). Hamısı eyni kompüterin F: diskindədir, şifrələnmir,
   başqa yerə köçürülmür. Gecə backup tapşırığı yoxdur; backup yalnız `go-public.ps1 start` və əl ilə alınır.
   `restore-test.ps1` yalnız sətir saylarını müqayisə edir. Yazılı bərpa planı (kim, nə qədər vaxt) yoxdur.
4. **Sayt düşəndə qaldıran yoxdur.** Nəzarətçi tapşırığı söndürülüb, prosesi işləmir. Quick tunnel ünvanı dəyişir.
5. **Gizlilik siyasəti və saxlama müddəti yoxdur.** Şəxsi məlumat (ad, email, telefon, doğum tarixi, müəssisə) toplanır,
   amma gizlilik siyasəti, istifadə şərtləri, məlumatın silinməsi və ya ixracı (GDPR tipli) yoxdur. Nəticələr, sessiyalar,
   audit və fırıldaq hadisələri üçün saxlama müddəti təyin olunmayıb.
6. **Stabil domen və server yoxdur.** Hər şey bir ev kompüterində, pulsuz Cloudflare quick tunnel ilə; SLA yoxdur,
   ünvan hər başlanğıcda dəyişir, 100 MB-dan böyük video Cloudflare-də kəsilir. Production compose yarımçıqdır.
7. **Birləşməmiş kod canlıdadır.** Canlı sayt B13 kodu ilə işləyir (exam V19, auth V8), `main` isə B12a-dadır.
   `main`-dən yenidən build etmək olmaz: köhnə kod yeni sxemlə qarşılaşar.
8. **Development portları LAN-a açıqdır** (compose: 8080–8084, 5432, 6379; Redis parolsuz). İctimai rejim bunları bağlayır.
9. **Gmail SMTP limiti.** İctimai rejim mail-i Gmail ilə göndərir; bot qeydiyyatı gündəlik limiti bitirə bilər
   (CAPTCHA yoxdur, AUDIT_GAP §1).

---

## 13. Backend var, frontend yox

Frontend repo yalnız oxundu (`feat/exam-lock-audit`, `79f7757`). Frontend `FE/services/api.ts` ilə `/api` bazasına
müraciət edir; `/api/auth/*` yolu nginx və vite tərəfindən `/auth/*`-a çevrilir. Çağırışlar `FE/services/*.ts` və
`FE/hooks/usePendingGradingCount.ts`-dən çıxarıldı.

**Ümumi rəqəmlər:** 120 backend endpoint-indən 82-ni frontend çağırır, 38-ni çağırmır. Çağırılmayanlardan 13-ü daxili
və ya texnikidir (b: 12 `/internal/**` və `GET /auth/health`), 21-i istifadəçi üçün lazımdır, amma ekranı yoxdur (a),
4-ü dublikatdır. Backend-də "köhnəlib, silinə bilər" (c) endpoint tapılmadı: dublikat görünən 4 endpoint-i
(`PUT /api/exams/{id}/audience`, `GET /api/exams/{id}/audience/preview`, tək təsdiq və tək rədd) regression skriptləri
işlədir (verify-b5-join, b7, b8, b13), ona görə onlar "c-namizəd" kimi qalır. Sayma: bölmə 3-ün "FE" sütunu.

### Boşluq cədvəli

| İmkan | Endpoint | Rol | Kat. | Prioritet | Faza |
|---|---|---|---|---|---|
| Müvəqqəti parolla ilk giriş: `mustChangePassword`-a baxıb parol dəyişmə ekranına aparmaq; `PASSWORD_CHANGE_REQUIRED`, `TEMPORARY_PASSWORD_EXPIRED` mətnləri | `POST /auth/login` cavabı, `POST /auth/change-password` | Müəllim | a | yüksək | F6 |
| Müəllim imtahanı PUBLIC göndərməsin: qaralama defaultu `accessType: 'PUBLIC'`-dir (`FE/lib/examDraft.ts` sətir 115), müəllim 403 `PUBLIC_EXAM_ADMIN_ONLY` alır, mətni yoxdur | `POST /api/exams`, `PUT /api/exams/{id}` | Müəllim | a | yüksək | F6 |
| Müəllim üçün qruplar ekranı (yaratmaq, üzv, link, sorğular). Menyu və marşrut yalnız admin üçündür (`FE/lib/adminMenu.ts`, `FE/App.tsx` sətir 136–141). Qrupsuz müəllim imtahanını heç kimə verə bilmir | `/api/admin/groups/**`, `/api/groups/**` | Müəllim | a | yüksək | F6 |
| Müəllim hesablarının idarəsi (yaratmaq, siyahı, söndürmək, parol sıfırlamaq) | `POST/GET /api/admin/instructors`, `PATCH /api/admin/instructors/{id}`, `POST .../reset-password` | Admin | a | yüksək | F7 |
| Hazırlıq yoxlaması və dərcin rəddinin izahı (`EXAM_NOT_READY` səbəbləri ilə) | `GET /api/exams/{id}/readiness`, `POST /api/exams/{id}/publish` | Admin, Müəllim | a | yüksək | F8 |
| Müəllimin iştirakçı siyahısı (yalnız öz üzvləri). Kart səhifəsi müəllimə açıqdır, siyahı səhifəsi `AdminOnlyRoute` altındadır (`FE/App.tsx` sətir 136–141) | `GET /api/admin/participants` | Müəllim | a | orta | F6 |
| `TEACHER_LIMIT_REACHED` (`{limit, max, used}`), `MEMBER_CODE_RATE_LIMITED`, `INSTRUCTOR_HAS_ACTIVE_EXAMS`, `TOKEN_REVOCATION_UNAVAILABLE` mətnləri | müxtəlif | Müəllim, Admin | a | orta | F6, F7 |
| Müəllim limitləri | `GET/PUT /api/admin/instructor-limits/{instructorId}` | Admin | a | orta | F7 |
| Hesab kilidini açmaq, email-i təsdiq etmək, istifadəçi axtarışı | `GET /auth/admin/users`, `POST .../unlock`, `POST .../verify-email` | Admin | a | orta | F7 |
| Klon ("nüsxə yarat"). Frontend-də ölü funksiya `/exams/{id}/duplicate` çağırır, belə endpoint yoxdur | `POST /api/exams/{id}/clone` | Admin, Müəllim | a | orta | F8 |
| Revizyon tarixçəsi | `GET /api/exams/{id}/history`, `/history/{revisionId}` | Admin, Müəllim | a | orta | F8 |
| Cəhdi olan imtahanın adını və təsvirini dəyişmək | `PATCH /api/exams/{id}/meta` | Admin, Müəllim | a | orta | F8 |
| Qoşulma sorğularım (status, `canRequestAgainAt`; `JOIN_REQUEST_COOLDOWN` frontend-də var) | `GET /api/join/requests/mine` | İştirakçı | a | orta | F9 |
| Dərc versiyaları və SHA-256 | `GET /api/exams/{id}/versions`, `/versions/{versionNo}` | Admin, Müəllim | a | aşağı | F8 |
| Son dəyişən (`lastModifiedBy`, `lastModifiedAt`) idarə siyahısında | `GET /api/exams/manage` | Admin, Müəllim | a | aşağı | F8 |
| Dəyişiklik səbəbi (`changeReason`, `CHANGE_REASON_REQUIRED`) | `PUT /api/exams/{id}` | Admin, Müəllim | a | aşağı | F8 |
| Blok nəticələri iştirakçıya (`blocks` nəticədə) | `GET /api/exams/session/{sessionId}/result` | İştirakçı | a | aşağı (B12b) | F8 |
| Müəllimin başlanğıc bayraqları (`hasGroups`, `hasExams`, `hasMembers`) | `GET /api/me/stats` | Müəllim | a | aşağı | F6 |
| Müəllimin öz audit jurnalı (menyu yalnız admin üçündür) | `GET /api/admin/audit` | Müəllim | a | aşağı | F6 |
| Profil şəkli (`feat/avatar` birləşəndən sonra) | `GET/DELETE /api/me/avatar`, `POST .../upload-url`, `POST .../confirm` | Hamı | a | aşağı | F9 |
| Sağlamlıq, daxili endpoint-lər, gateway fallback | `GET /auth/health`, 12 `/internal/**`, `/fallback/**` | – | b | – | – |
| Dublikat yollar (frontend create, update və toplu təsdiqlə edir) | `PUT /api/exams/{id}/audience`, `GET /api/exams/{id}/audience/preview`, tək approve və reject | – | c-namizəd, skriptlər işlədir | aşağı | – |

### Rol üzrə "backend imkanı var, ekranı yoxdur"

- **Admin:** müəllim hesabları; müəllim limitləri; hesab kilidini açmaq və email təsdiqi; hazırlıq yoxlaması;
  tarixçə; versiyalar; klon; meta düzəlişi; bloklar.
- **Müəllim:** müvəqqəti parolla ilk giriş; qruplar, üzvlər, linklər, sorğular; iştirakçı siyahısı; öz auditi;
  GROUP-u default edən yaradıcı; limit və kod-limit mesajları; başlanğıc bayraqları; hazırlıq, tarixçə, klon, meta.
- **İştirakçı:** qoşulma sorğularım; profil şəkli; blok nəticələri (B12b-dən sonra).

### Backend qaytarır, frontend göstərmir

`mustChangePassword` (login və `/auth/me`), `hasGroups`/`hasExams`/`hasMembers` (`/api/me/stats`),
`lastModifiedBy`/`lastModifiedAt` (`/api/exams/manage`), `timingMode`, `allowReturnToPreviousBlocks`,
`showBlockBreakdown`, `blocks` (`ExamResponse`, nəticə), `canRequestAgainAt` (sorğularım), versiyaların `sha256`-sı.
Frontend kodunda bu adların heç biri yoxdur (i18n və testlər çıxmaqla axtarıldı).

### Xəta kodları

| Kod | Backend | Frontend idarə edir |
|---|---|---|
| `PASSWORD_CHANGE_REQUIRED`, `TEMPORARY_PASSWORD_EXPIRED`, `TOKEN_REVOCATION_UNAVAILABLE` | var (B13) | yox |
| `TEACHER_LIMIT_REACHED`, `PUBLIC_EXAM_ADMIN_ONLY`, `MEMBER_CODE_RATE_LIMITED`, `INSTRUCTOR_HAS_ACTIVE_EXAMS`, `INSTRUCTOR_NOT_FOUND` | var (B13) | yox |
| `EXAM_NOT_READY`, `FEATURE_NOT_AVAILABLE`, `CHANGE_REASON_REQUIRED`, `BLOCKS_REQUIRED`, `EXAM_DURATION_EXCEEDS_WINDOW` | var (B12a) | yox |
| `EXAM_NOT_FOUND`, `SESSION_NOT_FOUND`, `GROUP_NOT_FOUND`, `AUDIENCE_GROUP_NOT_FOUND` (vahid 404) | var | bəli |
| `EXAM_NOT_OWNER` | B13-də silinib | frontend hələ saxlayır (`FE/lib/apiErrors.ts`), ölü kod |
| `EXAM_HAS_SESSIONS`, `DAILY_ATTEMPTS_REACHED`, `JOIN_REQUEST_COOLDOWN`, `GRADE_CONFLICT`, `FILTER_COUNT_CHANGED` və köhnə kodların çoxu | var | bəli |

Frontend tanımadığı kodda ümumi mesaj göstərir; bu ümumi davranış brauzerdə yoxlanılmadı.

### Əks istiqamət: frontend çağırır, backend-də yoxdur

Bu çağırışların hamısı `FE/services/examService.ts`-də ölü funksiyalardadır: heç bir ekran onları işlətmir, ona görə
istifadəçi sınıq görmür. Silinməlidir (F10, kateqoriya c, frontend tərəfdə).

| Frontend yolu | Funksiya | Backend-də |
|---|---|---|
| `POST /api/exams/{id}/duplicate` | `duplicateExam` | yox; real yol `POST /api/exams/{id}/clone` |
| `GET /api/exams/{id}/stats` | `getExamStats` | yox |
| `GET /api/admin/results` | `getExamResults`, `getAllResults` | yox |
| `GET /api/admin/stats` | `getAdminStats` | yox |
| `GET /api/admin/fraud-alerts` | `getFraudAlerts` | yox |
| `GET /api/admin/session/{id}/details` | `getSessionDetails` | yox |
| `GET /api/exams/categories` | `getCategories` | yox |
| `POST /api/fraud/report-violation`, `POST /api/fraud/record-events`, `GET /api/fraud/session/{id}/stats` | `reportViolation`, `reportSecurityEventsBatch`, `getFraudStats` | yox |
| `GET /api/exams/fraud/incidents`, `GET /api/exams/active-sessions` | `getAllFraudIncidents`, `getActiveSessions` | yox |

`FE/config/constants.ts`-də də `/exams/my-results`, `/fraud/alerts` kimi mövcud olmayan sabitlər var.

Qeyd (2026-10-22): yeni sual növləri bu cədvəldə yox idi, çünki backend-də hələ yox idi. İndi onların frontend-i
`exam-frontend`-in `feat/question-types` filialındadır (yaradıcı, imtahan ekranı, önizləmə, yoxlanış), backend ilə birlikdə
birləşdirilməlidir.

### Təklif olunan frontend fazaları

- **F6 – Müəllimin iş axını (B13):** ilk giriş və parol dəyişmə, GROUP default, qruplar və iştirakçılar menyusu,
  limit mesajları, başlanğıc bayraqları, öz auditi.
- **F7 – Admin: müəllimlər:** hesablar, limitlər, kilid və email təsdiqi.
- **F8 – İmtahan quruluşu (B12a):** hazırlıq, tarixçə, versiyalar, klon, meta, bloklar (B12b ilə birlikdə).
- **F9 – İştirakçı kabineti:** sorğularım, profil şəkli.
- **F10 – Təmizlik:** ölü servis funksiyaları, `EXAM_NOT_OWNER`, köhnə sabitlər.

Yoxlanılmadı: ekranların brauzerdə real davranışı (yalnız kod oxundu); frontend-in `main` filialı ayrıca yoxlanmadı
(`feat/exam-lock-audit` ondan 1 commit irəlidir və yalnız imtahan kilidi və auditlə bağlıdır).

---

## 14. Hazırkı vəziyyət

| Hissə | Status | Son dəyişiklik |
|---|---|---|
| Arxitektura (gateway, auth, exam) | hazır | 2026-10-20 |
| monitoring-service, websocket-service | planlaşıb (skelet) | – |
| Development stack | hazır | 2026-10-19 |
| İctimai rejim (quick tunnel) | hazır, müvəqqəti | 2026-10-21 (son start) |
| Nəzarətçi (watch-public) | yarımçıq (tapşırıq söndürülüb) | 2026-10-20 |
| Production compose, server, domen | planlaşıb | 2026-10-15 |
| Flyway: exam_auth V8 | hazır, canlıda | 2026-10-19 |
| Flyway: exam_db V23 | hazır, canlıda | 2026-10-22 |
| Qeydiyyat, OTP, giriş, parol bərpası | hazır | 2026-10-12 |
| Profil, iştirakçı kodu, iştirakçı kartı (B9–B11b) | hazır | 2026-10-19 |
| İmtahan, bloklar, tarixçə, snapshot, klon, hazırlıq (B12a) | hazır (backend) | 2026-10-19 |
| İştirakçı tərəfində blok qaydaları (B12b), blok redaktoru və imtahan ekranında bölmələr | hazır, canlıda | 2026-10-22 |
| Müəllim izolyasiyası, müəllim hesabları, limitlər (B13) | hazır, `main`-də deyil; sənədləri commit olunub | 2026-10-21 |
| Profil şəkli | hazır, `main`-də deyil | 2026-10-20 |
| Yeni sual növləri (backend və frontend) | hazır, `main`-də deyil, brauzerdə yoxlanılmayıb | 2026-10-22 |
| Endpoint reyestri və testləri | hazır | 2026-10-20 |
| Avtomatik testlər (224) | hazır | 2026-10-21 (işlədildi) |
| Regression skriptləri | hazır, B13-ə uyğunlaşdırılıb, B13-dən sonra işlədilməyib | 2026-10-21 |
| Backup və bərpa testi | yarımçıq (eyni disk, gecə tapşırığı yoxdur) | 2026-10-21 (son backup) |
| Test məlumatının təmizlənməsi | yarımçıq (quru qaçış edildi, `-Execute` sahibin əli ilə gözləyir) | 2026-10-21 |
| Gizlilik siyasəti, saxlama müddəti | planlaşıb | – |
| Frontend: B12a və B13 imkanları | planlaşıb (F6–F9) | – |
| Bu xəritə və qaydası | hazır | 2026-10-21 |

---

## 15. Açıq məsələlər və risklər (ən vacibdən başlayaraq)

1. İctimai saytda görünən 16 aktiv PUBLIC imtahanın hamısı `T-` testidir, 996 test hesabı canlı bazadadır. Real
   iştirakçılar bunları artıq verib: 2026-10-20-də iki real hesab (kodlar 7672474 və 6672457) `T-b12a` imtahanlarında
   3 cəhd edib. Quru qaçış (2026-10-21 17:47): 996 hesab, 153 imtahan, 53 qrup silinəcək; 76 hesab, 56 imtahan, 29 qrup
   qalacaq, #363 və #364 də qalır. `-Execute` canlı bazada təsdiq sözünü yalnız əl ilə qəbul edir (sahibin işi).
2. Canlı sayt və development eyni bazadır; regression skriptləri canlıya yazır. Staging bazası və ya ikinci stack lazımdır.
3. Backup yalnız eyni diskdədir, gecə tapşırığı yoxdur; kompüter xarab olsa hər şey itir.
4. Canlı sayt birləşməmiş B13 kodu ilə işləyir; `main` geri qalıb. `feat/b13-teacher-isolation` və `feat/avatar`
   birləşdirilməli, ya da canlı kod `main`-ə uyğunlaşdırılmalıdır.
5. Frontend müəllimi dəstəkləmir: müvəqqəti parolla daxil olan müəllim ilişib qalır (kod üzrə; brauzerdə
   yoxlanılmadı), PUBLIC default 403 verir, qrup ekranı yoxdur. Admin müəllim yarada bilmir (UI yoxdur).
6. Nəzarətçi söndürülüb; sayt düşəndə özü qalxmır, ünvan dəyişəndə iştirakçılar xəbər tutmur.
7. Müəllim iştirakçı kartında üzvün tam email-ini görür, qrup üzvləri siyahısında isə email maskalıdır. Bir qayda
   seçilməlidir (BACKLOG-dakı açıq gizlilik qərarı).
8. Gizlilik siyasəti, istifadə şərtləri və məlumat saxlama müddəti yoxdur.
9. Stabil domen və server yoxdur; production compose yarımçıqdır.
10. Development portları və parolsuz Redis LAN-a açıqdır.
11. B13-dən əvvəl müəllimlərin PUBLIC etdiyi 20 aktiv imtahan (14 COMPLETED, 4 ARCHIVED, 2 DRAFT; üstəlik yumşaq
    silinmiş #6) və müvəqqəti parolsuz 17 köhnə müəllim hesabı (qərar lazımdır).
12. Gateway-də ölü marşrutlar, iki boş servis, istifadə olunmayan iki token cədvəli, `MODERATOR` rolu.

---

## 16. Rəqəmlər haradan gəlir

| Rəqəm | Komanda |
|---|---|
| Endpoint reyestri 29 və 91 | `(Get-Content auth-service\src\test\resources\endpoint-access.txt) -notmatch '^\s*(#\|$)' \| Measure-Object` (bash: `grep -vcE '^\s*(#\|$)' <fayl>`) |
| Koddakı endpoint 29 və 91 | `grep -rhoE '@(Get\|Post\|Put\|Patch\|Delete)Mapping' --include=*.java <servis>/src/main \| wc -l` |
| Sinif bölgüsü | `grep -vE '^\s*(#\|$)' <fayl> \| awk '{print $3}' \| sort \| uniq -c` |
| Migrasiya faylları 8 və 20 | `ls <servis>/src/main/resources/db/migration \| wc -l` |
| Canlı Flyway versiyası | `docker exec exam-postgres psql -U postgres -d exam_db -At -c "select version from flyway_schema_history order by installed_rank"` |
| Cədvəllər, sütunlar, FK, indeks, trigger | `information_schema.columns`, `pg_constraint`, `pg_indexes`, `pg_trigger` üzərində `SELECT` |
| Sətir sayları, test məlumatı | `select count(*) ...`, `where email like '%@x.test'`, `where title like 'T-%'` |
| Testlər 224 | `.\mvnw.cmd -B clean test` (2026-10-21, 4:39, BUILD SUCCESS) |
| Test faylları 63, Testcontainers 32 | `find . -path '*/src/test/*' -name '*.java' \| wc -l`, `grep -rlE 'testcontainers\|PostgreSQLContainer' --include=*.java */src/test \| wc -l` |
| Filiallar | `git branch --merged main`, `git branch --no-merged main`, `git rev-list --count main..<filial>` |
| Planlı tapşırıqlar | `Get-ScheduledTask \| Get-ScheduledTaskInfo` |
| Frontend çağırışları | `grep -rnE -A2 "\bapi\.(get\|post\|put\|patch\|delete)\b" src --exclude-dir=__tests__` (frontend repo-da) |

---

## 17. Dəyişikliklər jurnalı

Ən yeni üstdə. Hər sətir: tarix (Bakı vaxtı), filial, nə dəyişdi, hansı hissələrə toxundu.

| Tarix (Bakı) | Filial | Nə dəyişdi | Hissələr |
|---|---|---|---|
| 2026-10-23 13:10 | `exam-frontend` `feat/question-types` | Ana səhifə yeniləndi: brend rəngləri #354788 (tünd göy) və #fcc16d (qızılı) tünd/açıq çalarları ilə; hero-da siçanı izləyən oxlar sahəsi (canvas, "reduce motion"-da hərəkətsiz, `components/home/ArrowField.tsx`), foto əvəzinə imtahan ekranının çəkilmiş önizləməsi (siçana yüngül meyl), bento kartlar (üzərinə gələndə qızılı xətt), qaydalar nömrəli, risk şkalası, tünd göy CTA və footer; yalnız /register və /login keçidləri; "CSV" yerinə "Excel"; frontend 643 test; sayt yeniləndi | 13 |
| 2026-10-23 13:15 | `exam-frontend` `feat/question-types` | İmtahan panelində suallar sətir siyahısı əvəzinə hər sırada 5 nömrəli kvadrat (44 px; 30 sual 6 sıra): cavablanan dolu, cari halqalı, işarələnən sarı nöqtəli; başlıqda "cavablanan/ümumi"; panel 256 px; frontend 643 test; sayt yeniləndi | 13 |
| 2026-10-23 13:00 | `exam-frontend` `feat/question-types` | İmtahan ekranında bölmələr yuxarıdan sol panelə köçdü: hər bölmə bir sətir (✓ bitib, ● açıq, 🔒 növbəti, cavablanan/ümumi say), açıq bölmənin sualları onun altında; bölmə bitəndə növbəti açılır; frontend 643 test; sayt yeniləndi | 13 |
| 2026-10-23 12:45 | `exam-frontend` `feat/question-types` | İmtahan ekranı "yan panelli müasir" tərtibatda (istifadəçinin seçimi; Bluebook, Moodle, Pearson VUE ilə müqayisədən sonra): ağ fon, yüksək kontrast; solda şaquli sual siyahısı (status dairəsi, cari sətir, bayraq, bağlı sual, açıq rejimdə bölmə başlıqları, altda izah və Bitir), sağda sual ağ səhifədə, altında Əvvəlki/Növbəti; yuxarıda ad, bölmə, taymer, irəliləyiş xətti; telefonda siyahı yuxarıdakı "3 / 10" düyməsi ilə açılan yan pəncərədir; frontend 643 test; sayt yeniləndi | 13 |
| 2026-10-23 12:20 | `exam-frontend` `feat/question-types` | İmtahan ekranı peşəkar və sakit: düz açıq-boz fon, yuxarıda sabit zolaq (ad, bölmə, taymer, nazik irəliləyiş xətti, bölmə addımları), sual ağ vərəqdə animasiyasız, aşağıda sabit naviqasiya zolağı (Əvvəlki, 3/10, saxlanıldı, Növbəti/Bitir), sual xəritəsi sadə kvadratlar; fasilə ekranı və təlimat neytral rəngdə; imtahan haqqında səhifə qradiyentsiz, bir ağ kartda (faktlar, vaxt, qaydalar, başla düyməsi); frontend 643 test; sayt yeniləndi | 13 |
| 2026-10-23 11:45 | `main` (backend `fix/results-sheet-email`), `exam-frontend` `feat/question-types` | Excel: başlıqlarda "(Bakı vaxtı)" yoxdur, tam bal "2." yox "2" görünür (General format); nəticələr xülasəsinin kartlarında (ən yaxşı, ən zəif, pozuntular) adın altında e-poçt (`Scorer.email`); backend exam 186, frontend 643 test; sayt yeniləndi | 5, 13 |
| 2026-10-23 12:05 | `exam-frontend` `feat/question-types` | Düzəliş: bir tabda admin, digərində iştirakçı daxil olanda yeniləmə admini iştirakçıya çevirirdi (giriş ortaq localStorage-də idi). İndi hər tab öz girişini sessionStorage-də saxlayır, yeni tab sonuncu girişlə açılır, eyni şəxsin tabları yenilənmiş tokenləri bir-birindən götürür (`store/authStore.ts`); frontend 643 test; sayt yeniləndi | 13 |
| 2026-10-23 11:45 | `exam-frontend` `feat/question-types` | Audit: ada klikləyəndə süzgəc çipi əvəzinə həmin şəxsin ayrıca pəncərəsi açılır (sağdan, adı, ID, hərəkət sayı, səhifələnən siyahı; səhifənin süzgəci dəyişmir); axtarışda "(ən azı 2 simvol)" yoxdur; menyuda "Açıq suallar" yerinə "Qiymətləndirmə"; frontend 640 test; sayt yeniləndi | 13 |
| 2026-10-23 11:20 | `exam-frontend` `feat/question-types` | Audit jurnalı: beş sütunlu cədvəl əvəzinə hər hadisə bir sətir (baş hərflər, ad (kliklə həmin şəxsə süzgəc), hərəkət, nəyə, vaxt), altında ID və mənbə; `key=value` xülasəsi sözlərlə nişanlı çiplər (Bəli/Xeyr, Bakı vaxtı, status adları); backend dəyişmədi; frontend 640 test; sayt yeniləndi | 13 |
| 2026-10-23 11:00 | `exam-frontend` `feat/question-types` | İmtahan taymeri kiçildi və sakitləşdi (ağ kart, nazik çərçivə, 2xl/3xl rəqəm; son 5 dəqiqədə sarı, son dəqiqədə qırmızı qalır); backend dəyişmədi; sayt yeniləndi | 13 |
| 2026-10-23 10:45 | `main` (backend `feat/results-xlsx`), `exam-frontend` `feat/question-types` (`d117504`) | Nəticələrin ixracı Excel faylı kimi: `GET /api/exams/{id}/results/export?format=xlsx` (kitabxanasız `ES/export/XlsxSheet.java`: sütun enləri, qalın rəngli başlıq ekranda qalır, süzgəc, Bakı vaxtı tarix xanası, rəqəmlər rəqəm, Azərbaycanca status/risk/qiymətləndirmə, sessiya ID sonda, formula yoxdur); CSV (`format=csv`, default) əvvəlki kimi; frontend "Excel-ə yüklə"; iştirakçı kartında Profil əvvəl, Əlaqə sonra, "(Bakı vaxtı ilə)" yoxdur, son nəticə statistika kartıdır; backend exam 186, frontend 638 test; sayt yeniləndi | 5, 13, 14 |
| 2026-10-22 17:40 | `main` (backend `feat/option-media-kinds`), `exam-frontend` `feat/question-types` (`b6c4a51`) | Variant da bir şəkil, səs və ya video daşıya bilər (`ExamMediaService`-də "yalnız şəkil" qaydası götürüldü, oynatma limiti yoxdur); yaradıcıda variant sətrində yazısız üç ikon, "Variant əlavə et" yerinə "+"; backend 183 (exam), frontend 638 test; sayt yeniləndi | 13, 14 |
| 2026-10-22 17:10 | `main` (backend `feat/b12b-session-blocks`), `exam-frontend` `feat/question-types` (`78b551b`, `69e58db`) | B12b: iştirakçı tərəfində bloklar (blok taymerləri, fasilə, bağlanan bloklar, "yalnız irəli" bloklar, exam V23 `session_block_progress`, 4 yeni endpoint, `EXAM_BLOCK_RULES_ENABLED` default true); frontend: yaradıcıda bölmə redaktoru və vaxt rejimi, imtahan ekranında bölmə addımları, fasilə ekranı, "Bölməni bitir"; rəqəmli səhifələmə; sual mediası mətnin altında Şəkil/Səs/Video düymələri; backend 266, frontend 638 test; canlı baza V23, sayt yeniləndi | 3, 5, 13, 14 |
| 2026-10-22 16:40 | `exam-frontend` `feat/question-types` (`0127aaa`) | Başlıq altı izahlar silindi: Audit, Nəticələr, Açıq suallar, imtahanın önizləməsi ("Yalnız oxuma rejimi..."); iştirakçı kartında "o cümlədən müvəqqəti", qoşulma kartında "Limit" çipi yoxdur; alt yazısız başlıq düymələrlə bir xətdədir; backend dəyişmədi; frontend 623 test; sayt yeniləndi | 13, 14 |
| 2026-10-22 16:15 | `exam-frontend` `feat/question-types` (`d4cd453`) | Qruplar: siyahının üstündəki izah qutusu, "İmtahanlar" və "Gözləyən dəvətlər" bölmələrindəki qeydlər silindi; qoşulma kartı yenidən quruldu ("Status: Aktiv", sayğaclar başlıqda, "Son tarix" yanında "Tarixsiz", "İstifadə limiti" yanında "Limitsiz" düyməsi); backend dəyişmədi; frontend 623 test; sayt yeniləndi | 13, 14 |
| 2026-10-22 13:10 | `main` (backend `feat/participant-search-fields`), `exam-frontend` `feat/question-types` (`c85118c`) | İştirakçı siyahısında ayrı axtarışlar: `name`, `email`, `code` (kodun başlanğıcı, unikal indeksin aralığı); frontend: üç axtarış qutusu, adlar sahələrin içində, "Hamısı" və "Qrupsuz" yoxdur, "Süzgəci təmizlə" solda; backend 243, frontend 622 test; sayt yeniləndi, tunel ünvanı dəyişdi | 3, 13, 14 |
| 2026-10-22 12:00 | `main` (backend `feat/bulk-already-members`) və `exam-frontend` `feat/question-types` (`8e73dd7`) | Qrupa kütləvi əlavədə cavab artıq üzv olanları adı və kodu ilə qaytarır (`alreadyMembersSample`, ilk 50; sənəd 5: `BulkResult`); frontend: bir düymə ilə yoxla və əlavə et, yeni siyahı; imtahan taymeri böyüdüldü; vacib sahələrdə `*`, "istəyə bağlı" yazıları silindi; idarə panelindən iki düymə və qoşulma linki başlığı çıxarıldı; backend 239 test keçdi, frontend 619; sayt yeniləndi | 3, 13, 14 |
| 2026-10-22 11:21 | `exam-frontend` `feat/question-types` (`8374cfd`) | Tarix sahələri adı içində, mm/dd/yyyy maskası olmadan; "İmtahanlar" açılan menyu (İmtahanlara bax, İmtahan yarat, İmtahanı qiymətləndir); sual mediası sual mətninin altında; Diapazonda "(istəyə bağlı)" çıxarıldı; frontend yenidən yükləndi (backend dəyişmədi) | 13, 14 |
| 2026-10-22 11:05 | `main` | `feat/avatar`, `docs/project-map`, `feat/question-types` `main`-ə birləşdi; `clean package` (237 test keçdi), exam-service image yeniləndi, `go-public.ps1 start` (backup, ünvan dəyişmədi, tüstü testləri keçdi); canlı bazada exam V20, V21, V22; sayt frontend `feat/question-types` (`0d59528`) build-ini verir | 2, 10, 14 |
| 2026-10-22 10:42 | `feat/question-types` (backend və `exam-frontend`), `docs/project-map` | Sual növləri: Sıralama, Uyğunlaşdırma, Qısa cavab tamamlandı, Diapazon (LINEAR_SCALE, exam V22) əlavə olundu; yaradıcıda doqquz növ, "Tək seçimli" və "Çox seçimli" adları, "Tezliklə" yoxdur; imtahan ekranı, önizləmə və yoxlanış; backend 237, frontend 618 test | 0, 2, 5, 9, 10, 13, 14 |
| 2026-10-21 17:53 | `feat/b13-teacher-isolation`, `feat/question-types`, `docs/project-map` | B13 sənədləri (`d9dd6d9`, 31→29 düzəlişi ilə) və B13-ə uyğunlaşdırılmış regression skriptləri, README-də verify-b13 (`6acab43`) b13 filialına köçürüldü; sual növləri WIP commit oldu (`97d47a4`), işçi qovluq təmizdir; b13 ucunda 215 test keçdi; birləşmə sırası konfliktsiz sınandı; test məlumatının quru qaçışı, #363/#364, müəllimlərin PUBLIC imtahanları, kartın gizliliyi | 0, 2, 3, 4, 5, 6, 8, 10, 11, 14, 15 |
| 2026-10-21 16:08 | `docs/project-map` | İlk analiz: backend başdan sona (`cb19e65`), canlı baza yalnız oxundu, 224 test işlədildi, frontend boşluq analizi; PROJECT_MAP.md, PROJECT_RULES-a xəritə qaydası, `scripts\check-project-map.ps1` | hamısı (0–18) |

---

## 18. Bu sənədi necə yeniləmək

1. Tapşırıq bitəndə, commit-dən əvvəl: dəyişdiyin hissənin bölməsini düzəlt (məsələn, yeni endpoint → bölmə 3 və 13,
   yeni migrasiya → bölmə 2, yeni skript → bölmə 8).
2. Bölmə 14-də statusu və tarixi yenilə.
3. Bölmə 17-nin başına yeni sətir əlavə et.
4. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-project-map.ps1` işlət. Kod dəyişib, bu sənəd
   dəyişməyibsə xəbərdarlıq verəcək (çıxış kodu 1). `-Base <filial>` ilə müqayisə bazasını dəyişmək olar (default `main`),
   `-Staged` ilə yalnız commit üçün hazırlanmış dəyişikliklərə baxır.
