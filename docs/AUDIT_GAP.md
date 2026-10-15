# İmtahan modulunun boşluq auditi (Moodle Quiz, Canvas, Blackboard, TestPortal, Mettl, ProctorU ilə müqayisə)

Tarix: 2026-10-15. Baza: `main` (`47c9d60`), exam-service miqrasiyaları V1–V13, auth-service V1–V2,
frontend `exam-frontend` (React + Vite + TS). Yalnız kod oxunub; heç nə işlədilməyib və ya
dəyişdirilməyib. Hər sübut fayl:sətir, endpoint və ya miqrasiya ilə verilib. Tapılmayan "YOX",
tapılıb amma tam olmayan "QİSMƏN" kimi qeyd olunub.

Yollar qısaldılıb:
- `ES/` = `exam-service/src/main/java/com/examportal/exam/`
- `AS/` = `auth-service/src/main/java/com/examportal/auth/`
- `CM/` = `common/src/main/java/com/examportal/common/`
- `FE/` = `exam-frontend/src/`
- `M/V*` = exam-service miqrasiyası

Prioritet: P0 = ictimai istifadədən əvvəl mütləq, P1 = vacib, P2 = gözəl olar.
Həcm: S ≤ 1 gün, M 2–4 gün, L ≥ 1 həftə.

---

## 1. Qısa icmal

Əsas axın möhkəmdir:
- Server tərəfi taymer, atomik cəhd limiti, idempotent təhvil.
- İmtahan zamanı düzgün cavab sızmır.
- Qruplar və sahiblik qaydaları, maddə analizi.

Moodle səviyyəsindən əsasən bu sahələrdə geri qalır: sual növləri, sual bankı, regrade, override,
iştirakçının öz cavablarına baxması, rol idarəsi və bildirişlər.

**Ən təhlükəli 5 tapıntı:**
1. **Qeydiyyat və OTP bot müdafiəsi zəifdir (P0).**
   - CAPTCHA yoxdur.
   - Qeydiyyat limiti IP başına 500/s, burst 1000-dir.
   - Hər qeydiyyat Gmail SMTP ilə məktub göndərir. Bot Gmail-in gündəlik limitini bitirsə, heç kim qeydiyyatdan keçə bilmir.
2. **Bir hesab eyni sessiyanı bir neçə cihazdan eyni vaxtda işlədə bilir (P0).** Cihaza bağlama yoxdur, ona görə hesabı paylaşmaq və ya başqasının əvəzinə imtahan vermək aşkarlanmır.
3. **Nəticəsi olan imtahanın sualları dəyişə bilər, regrade isə yoxdur (P0).** Reschedule COMPLETED və ya ARCHIVED imtahanı SCHEDULED-ə qaytarır. Bundan sonra update sualları sərt silib yenidən yaradır. Saxlanmış ballar yenidən hesablanmır.
4. **Profil səhifəsi mövcud olmayan endpointlərə müraciət edir (P0).** `/auth/me`, `/auth/change-password` və profil yeniləmə backend-də yoxdur. Parol dəyişmək yalnız "unutdum" axını ilə mümkündür.
5. **Rol idarəsi və admin qorunması yoxdur (P1).**
   - INSTRUCTOR rolu yalnız SQL ilə verilir.
   - ADMIN və INSTRUCTOR üçün 2FA yoxdur.
   - Sessiyalar idarə olunmur: refresh token 7 gün yaşayır.

---

## 2. A–E cədvəlləri

### A) İmtahan quruluşu

| Funksiya | Status | Sübut | Prio | Həcm | Qeyd |
|---|---|---|---|---|---|
| Tək seçimli | VAR | `CM/.../QuestionType` MULTIPLE_CHOICE; `ES/validator/ExamValidator.java:116-122` | – | – | ≥2 variant |
| Çoxlu düzgün cavab | VAR | `M/V1` `questions.allow_multiple_answers`; `ES/service/AnswerSubmissionService.java:313,365-368` | – | – | Bal "hamısı və ya heç nə" |
| Doğru/yalan | VAR | TRUE_FALSE; `ExamValidator.java:147-150` (tam 2 variant) | – | – | |
| Qısa cavab / fill-in-blank | QİSMƏN | FILL_BLANK; `AnswerSubmissionService.java:536-545` | P1 | M | Yalnız dəqiq uyğunluq yoxlanır (böyük-kiçik hərf və kənar boşluqlar nəzərə alınmır). Bir neçə qəbul olunan cavab variant kimi verilir. Wildcard, regex, ədədi tolerans və bir sualda bir neçə boşluq yoxdur |
| Esse | VAR | OPEN_ENDED; `M/V8`; `ES/service/GradingService.java` | – | – | Əl ilə yoxlanır |
| Uyğunlaşdırma (matching) | YOX | Backend enum-da yoxdur; `FE/lib/examDraft.ts:19` "UPCOMING" | P2 | M | UI-da "tezliklə" kimi görünür |
| Sıralama (ordering) | YOX | eyni | P2 | M | |
| Numerical (tolerans) | YOX | enum-da yoxdur | P2 | S | FILL_BLANK üzərində qurmaq olar |
| Hesablama (calculated) | YOX | – | P2 | L | Lazım olmaya bilər (bax §5) |
| Drag-drop | YOX | – | P2 | L | |
| Sualda media (şəkil, audio, video) | VAR | `M/V4`; `ES/service/SessionMediaService.java`; maxPlays `ES/repository/MediaLinkRepository.java:180-184` | – | – | |
| Sual bankı (imtahandan ayrı) | YOX | `M/V1` `questions.exam_id NOT NULL` | P1 | L | Suallar yalnız bir imtahana aiddir |
| Kateqoriya və teqlər | QİSMƏN | `M/V1` `categories`, `exams.category_id`; `ES/mapper/ExamMapper.java:38` | P1 | M | Kateqoriyaların API-si yoxdur. Suallarda kateqoriya və teq yoxdur |
| Import/export (CSV, GIFT, Moodle XML, Aiken) | YOX | Heç bir format tapılmadı | P1 | M | CSV yalnız qrup üzvləri üçündür |
| İmtahanı kopyalamaq və şablon | YOX | ExamController-də endpoint yoxdur | P1 | S | |
| Sual versiyalama | YOX | `ES/service/ExamService.java:142-144, 720-724` | P1 | M | Update sualları sərt silib yenidən yaradır |
| Bankdan təsadüfi seçim, çətinliyə görə | YOX | – | P1 | M | Sual bankı tələb olunur |
| Sualları və variantları qarışdırmaq | VAR | `ExamService.java:861-875`; sessiyaya görə keşlənir `ES/service/ExamCacheService.java` | – | – | Redis itsə sıra yenidən qarışır (§6) |
| Sual başına bal | VAR | `M/V2` DECIMAL(7,2) | – | – | |
| Mənfi bal | YOX | `AnswerSubmissionService.java:500-503` (səhv cavab = 0) | P1 | S | |
| Qismən bal (çoxlu seçim) | YOX | `AnswerSubmissionService.java:547-553` | P1 | S | Esse üçün əl ilə qismən bal VAR (`GradingService.java:155`) |
| Bal metodu (ən yüksək, orta, son) | QİSMƏN | Nəticə siyahısında `bestOnly` (ən yaxşı cəhd); `ES/service/ExamResultsService.java:100-104` | P2 | S | İmtahan üçün ayrıca ayar yoxdur |
| Keçid balı | VAR | `M/V1` `passing_score`; `AnswerSubmissionService.java:522` | – | – | |
| Qiymət aralıqları (A/B/C, mətn) | YOX | – | P2 | S | |
| Açılma və bağlanma | VAR | `start_time`/`end_time`; `ES/service/ExamSchedulerService.java:29-77` | – | – | Scheduler 60 saniyədən bir işləyir |
| Müddət | VAR | `ExamService.java:764-770` | – | – | Pəncərənin sonu ilə kəsilir (F1) |
| Vaxt bitəndə avto-təhvil | VAR | `ExamSchedulerService.java:76-77`; `AnswerSubmissionService.java:72-77, 280-291` | – | – | |
| Gecikmə müddəti (grace) | YOX | – | P2 | S | |
| Fərdi və ya qrup üzrə vaxt uzatma | YOX | – | P1 | M | `ExamCacheService.java:181` `extendSessionTTL` var, amma heç yerdən çağırılmır |
| Cəhd sayı | VAR | `max_attempts`; atomik yoxlanır `ES/repository/ExamAttemptRepository.java:35-70` | – | – | |
| Gündəlik cəhd limiti | VAR | `M/V7` | – | – | |
| Cəhdlər arası fasilə | YOX | – | P2 | S | |
| Fərdi və ya qrup üzrə cəhd override-ı | YOX | – | P1 | M | Reschedule yalnız hamı üçün limiti artırır (`ExamService.java:416`) |
| Parol ilə giriş | YOX | – | P2 | S | |
| IP aralığı | YOX | – | P2 | S | |
| Safe Exam Browser | YOX | – | P2 | M | |
| Yalnız seçilmiş qruplar | VAR | `M/V3`; `ES/service/ExamAccessPolicy.java` | – | – | |
| Əvvəlki imtahandan keçmə şərti | YOX | – | P2 | S | |
| Sual sırası | VAR | order_index və ya qarışdırma | – | – | |
| Geri qayıtma icazəsi (ardıcıl rejim) | YOX | Seçim yoxdur, həmişə sərbəstdir | P2 | S | |
| Sualı bayraqlamaq | QİSMƏN | `FE/pages/exam/TakeExam.tsx:70` (`toggleFlag`) | P2 | S | Yalnız müştəridə saxlanır, serverə getmir |
| Bir səhifədə neçə sual | QİSMƏN | Partiya ilə yüklənir (`ExamService.java:804`) | P2 | S | Ayar yoxdur |
| Naviqasiya paneli | QİSMƏN | TakeExam.tsx | – | – | Detallar yoxlanmayıb (§6) |
| Arxiv | VAR | `POST /api/exams/{id}/archive` | – | – | |
| Dəyişiklik kilidi | QİSMƏN | `CM/.../Exam.java:113` (DRAFT və SCHEDULED) | P0 | S | Reschedule ilə aşılır (F5) |
| Sual səviyyəsində rəy (izahat) | QİSMƏN | `questions.explanation`; yalnız idarə edənə görünür (`ES/mapper/ExamMapper.java:187`; `ExamResultsService.java:246-253`) | P1 | S | İştirakçı heç vaxt görmür |
| Ümumi rəy (bal aralığına görə) | YOX | – | P2 | S | |
| Review seçimləri (nə və nə vaxt göstərilir) | QİSMƏN | Yalnız `show_results_immediately`, ya da imtahan bitəndən sonra (`AnswerSubmissionService.java:217-221`) | P1 | M | Cavab, düzgün cavab və izahat göstərilmir |

### B) Yoxlama və nəticə

| Funksiya | Status | Sübut | Prio | Həcm | Qeyd |
|---|---|---|---|---|---|
| Açıq sualların əl ilə yoxlanması | VAR | `GET/PUT /api/exams/session/{id}/grades`, `/api/grading/*`; `M/V8` | – | – | Optimistik versiya (`GradingService.java:159`), audit |
| Bir neçə yoxlayan, rubrika, ikili yoxlama | YOX | Hər cavabda tək `graded_by` (`M/V8`) | P2 | L | |
| Bir cavabın yenidən qiymətləndirilməsi | VAR | ANSWER_REGRADED (`GradingService.java:178`) | – | – | |
| Sualı ləğv etmək, bonus vermək, açarı dəyişib hamını yenidən hesablamaq | YOX | Bal yalnız təhvil anında hesablanır (`AnswerSubmissionService.java:381-397`) | P0 | M | Səhv açarı yalnız SQL ilə düzəltmək olar |
| İştirakçının etirazı (apellyasiya) | YOX | – | P2 | M | |
| Çətinlik və ayırdetmə indeksi | VAR | `GET /api/exams/{id}/analytics/questions`; `ES/service/ExamAnalyticsService.java` | – | – | |
| Distraktor analizi | VAR | Variant seçilmə payları (analytics questions) | – | – | |
| Etibarlılıq (KR-20, Cronbach) | YOX | – | P2 | S | |
| Bal paylanması, median | VAR | `/analytics/overview` scoreDistribution; `ES/repository/AnalyticsRepository.java:214` | – | – | |
| CSV export | VAR | `GET /api/exams/{id}/results/export` | – | – | Limit 50 000 sətir |
| XLSX və PDF export | YOX | – | P2 | M | |
| Toplu nəticə (summary) | VAR | `GET /api/exams/{id}/results/summary` | – | – | |
| Cəhdlərin müqayisəsi | QİSMƏN | `bestOnly=false` bütün cəhdləri verir | P2 | S | |
| Sertifikat | YOX | – | P2 | M | |
| Reytinq və persentil | YOX | Yalnız median | P2 | S | |
| Nəticənin dərcini idarə etmək | QİSMƏN | `show_results_immediately` və ya imtahanın sonu | P1 | S | "Gizli saxla", "bu tarixdə aç" yoxdur |

### C) İmtahan keçirmə bütövlüyü

| Funksiya | Status | Sübut | Prio | Həcm | Qeyd |
|---|---|---|---|---|---|
| Server tərəfi taymer | VAR | `exam_sessions.end_time`; `FE/pages/exam/TakeExam.tsx:49,298` (clockOffset) | – | – | |
| Avtomatik saxlama | VAR | Hər cavab ayrıca POST olunur; lokal növbə `FE/lib/answerQueue` | – | – | |
| Bağlantı kəsiləndə davam etmək | VAR | Start mövcud sessiyanı və savedAnswers-i qaytarır (`ExamService.java:321-328, 821-826`) | – | – | |
| İkiqat təhvil | VAR | `markCompleted` şərtlidir (`AnswerSubmissionService.java:381-397`) | – | – | |
| Bir hesab, iki cihaz/tab | QİSMƏN | `M/V1` `uq_sessions_one_in_progress` | P0 | M | İkinci cihaz eyni sessiyanı alır, cihaza bağlama yoxdur (F3) |
| Düzgün cavabın müştəriyə sızması | VAR (sızma yoxdur) | `ExamMapper.java:174-187, 276-284`; `ExamService.java:849-851` | – | – | F6 |
| Fokus itkisi, tab, copy/paste, tam ekran, DevTools | VAR | `M/V6`; `POST .../fraud-event`; `ES/service/FraudRiskPolicy.java` | – | – | Risk balı da var |
| IP və cihaz izləmə | QİSMƏN | `exam_sessions.ip_address/browser_info` müştərinin göndərdiyi gövdədən götürülür (`CM/.../StartExamRequest`) | P1 | S | Saxtalaşdırıla bilər, serverin gördüyü IP-dən götürülməlidir |
| Kamera və ekran proctoring | YOX | – | P2 | L | §5-ə bax |
| Şəxsiyyət yoxlaması | YOX | – | P2 | L | |
| Gözləmə otağı, startların paylanması | YOX | – | P1 | M | Yük altında yoxlanmayıb (§6) |
| Müşahidəçi (proctor) rolu | YOX | Rollar ADMIN, INSTRUCTOR, STUDENT | P1 | M | |
| Canlı monitorinq | VAR (yalnız oxumaq) | `ES/controller/MonitoringController.java` (active, per-exam, violations) | – | – | |
| Mesaj göndərmək, dayandırmaq, vaxt əlavə etmək | YOX | – | P1 | M | `websocket-service` yalnız heartbeat göndərən stub-dır (`ExamWebSocketHandler.java:46-50`) |

### D) İştirakçı tərəfi

| Funksiya | Status | Sübut | Prio | Həcm | Qeyd |
|---|---|---|---|---|---|
| Şəxsi panel, tarixçə, nəticələr | VAR | `FE/pages/dashboard/{Dashboard,ExamList,Results}.tsx`; `GET /api/exams/my-sessions` | – | – | |
| Təqvim | YOX | – | P2 | S | |
| Geri sayım | VAR | `FE/__tests__/countdown.test.tsx` | – | – | |
| Qaydalar səhifəsi | QİSMƏN | `FE/pages/exam/ExamDetail.tsx` (imtahanın təsviri) | P2 | S | Ümumi qaydalar mətni yoxdur |
| Sistem yoxlaması (brauzer, internet) | YOX | – | P2 | S | |
| Sınaq imtahanı (practice) | YOX | – | P2 | S | Adi imtahan kimi qurmaq olar |
| Naviqasiya, bayraq, qalan vaxt | QİSMƏN | TakeExam.tsx | – | – | Bayraq yalnız müştəridədir |
| Mobil uyğunluq, əlçatanlıq | QİSMƏN | `FE/__tests__/modalA11y.test.tsx` | P2 | M | Ekran oxuyucu və kontrast yoxlanmayıb |
| Çoxdillilik | VAR | `FE/i18n/index.tsx` (az, ru, en) | – | – | Backend mesajları ingiliscədir |
| Öz cavablarına baxmaq (review) | YOX | Review yalnız idarə edənə açıqdır (`ExamResultsService.java:246-253`) | P1 | M | |
| Yoxlayanın rəyini görmək | YOX | `feedback` yalnız idarəetmə cavablarında var | P1 | S | |
| Etiraz, sertifikat | YOX | – | P2 | M | |
| Profil: məlumat, parol, e-poçt | QİSMƏN (qırıq) | `FE/pages/dashboard/Profile.tsx:69-104` → `/auth/me`, `/auth/change-password` (`FE/config/constants.ts:35-36`); `AS/controller/AuthController.java`-da yoxdur | P0 | M | UI var, backend yoxdur |
| Sessiyaların idarəsi | YOX | Refresh token yalnız logout və reset-də ləğv olunur (`AS/service/AuthService.java:198-244`) | P1 | S | |
| Məlumatın yüklənməsi və silinməsi (GDPR tipli) | YOX | – | P2 | M | |
| E-poçt bildirişləri | QİSMƏN | Yalnız OTP və parol sıfırlama (`AS/mail/VerificationMailer.java`) | P1 | M | Xatırlatma, qrup qərarı, "nəticə hazırdır" bildirişləri yoxdur |
| Bildiriş mərkəzi | QİSMƏN | `FE/components/common/NotificationDropdown.tsx` + lokal store | P2 | M | Backend yoxdur |

### E) Admin idarəetməsi

| Funksiya | Status | Sübut | Prio | Həcm | Qeyd |
|---|---|---|---|---|---|
| İstifadəçilərin toplu importu | YOX | Qrup CSV-si yalnız üzv və dəvət əlavə edir | P2 | M | |
| İstifadəçi exportu | YOX | – | P2 | S | |
| Rol təyini (INSTRUCTOR) | YOX | `AS/controller/AdminUserController.java` (yalnız users, unlock, verify-email) | P1 | S | Yalnız SQL ilə; audit qalmır |
| Bloklama | QİSMƏN | Avtomatik lockout (5 səhv / 15 dəq); admin yalnız unlock edə bilir | P1 | S | Admin əl ilə bloklaya bilmir |
| E-poçtu əl ilə təsdiqləmək | VAR | `POST /auth/admin/users/{id}/verify-email` | – | – | |
| Admin tərəfindən parol sıfırlama | YOX | – | P2 | S | |
| Dublikat hesabları birləşdirmək | YOX | – | P2 | L | |
| Qruplar | VAR | `M/V3`, `M/V10`, `M/V12`; join link və sorğular | – | – | |
| Çox səviyyəli qruplar, avtomatik qaydalar | YOX | – | P2 | M | |
| Əlavə rollar (müşahidəçi, yoxlayan, redaktor, hesabat) | YOX | 3 rol | P1 | M | |
| ADMIN və INSTRUCTOR üçün 2FA | YOX | – | P1 | M | |
| Audit jurnalı | VAR | `group_audit_log`, `admin_audit_log`, `GET /api/admin/audit` | – | – | Login hadisələri və sual dəyişiklikləri yazılmır |
| Retention və silmə siyasəti | QİSMƏN | OTP 7 gün, təsdiqlənməmiş hesab 14 gün sonra deaktiv olur (`auth application.yml:104-105`) | P2 | S | Nəticələr üçün siyasət yoxdur |
| Soft delete və bərpa | QİSMƏN | Qruplar soft delete olunur. İmtahan yalnız sessiyası yoxdursa sərt silinir (`ExamService.java:158-184`) | P2 | S | Bərpa UI-ı yoxdur |
| Sayt adı, loqo, e-poçt şablonları, qaydalar mətni | YOX | Kodda sabit yazılıb | P2 | M | |
| Qeydiyyatda əlavə sahələr | QİSMƏN | `users.phone_number` (auth V1) | P2 | M | Şəhər, məktəb, FİN yoxdur |
| Backup və bərpa | VAR | `scripts/backup.ps1`, `scripts/restore-test.ps1` | – | – | |
| Monitorinq və xəbərdarlıq | QİSMƏN | actuator; `monitoring-service` boş tətbiqdir (18 sətir) | P1 | M | Xəbərdarlıq (alert) yoxdur |
| Staging mühiti | YOX | – | P1 | M | |
| Test datasının canlı datadan ayrılması | QİSMƏN | `scripts/cleanup-test-data.ps1` (`@x.test`, `T-`) | – | – | |

---

## 3. F: məntiq və bütövlük sualları

1. **Pəncərə 18:00-da bitir, iştirakçı 17:30-da 90 dəqiqəlik cəhdə başlayır.**
   - Sessiya 18:00-da bitir: `endTime = min(now + duration, exam.endTime)` (`ES/service/ExamService.java:764-770`).
   - Moodle-un standart davranışı da belədir.
   - Boşluq: iştirakçıya başlamazdan əvvəl "cəmi 30 dəqiqə qalıb" xəbərdarlığının olub-olmadığı yoxlanmayıb. **P2.**
2. **İştirakçı imtahan zamanı qrupdan çıxarılsa:**
   - Başlanmış sessiya davam edir. Cavab və təhvil yalnız sessiyanın sahibini yoxlayır (`AnswerSubmissionService.java:67-83`).
   - Yeni start isə imtahana girişi yoxlayır və rədd edilir (`ExamService.java:314-316`).
   - Bu, şüurlu seçimdir. "Dərhal kəs" seçimi yoxdur. **P2.**
3. **İki cihaz və ya tabdan paralel cəhd: mümkündür.**
   - Bir anda yalnız bir aktiv sessiya ola bilər (`M/V1` `uq_sessions_one_in_progress`).
   - Amma start eyni sessiyanı ikinci cihaza da qaytarır (`ExamService.java:321-328`).
   - Hər iki cihaz cavab yaza bilir. Son yazılan cavab qalır: upsert, `UserAnswerRepository.java:61`.
   - Cihaz tokeni, heartbeat və "başqa cihazda açıldı" hadisəsi yoxdur. **P0.**
4. **İkiqat "Təhvil ver" və paralel saxlama: idempotentdir.**
   - Təhvil: `markCompleted ... filter(updated == 1)` (`AnswerSubmissionService.java:381-397`). İkinci çağırış saxlanmış nəticəni qaytarır (`:93-108`).
   - Cavab: tək şərtli upsert (`UserAnswerRepository.java:61`). Sessiya bağlanıbsa 409/410 qaytarılır (`AnswerSubmissionService.java:328, 342-347`).
5. **Aktiv və ya nəticəsi olan imtahanda sualı və düzgün cavabı dəyişmək:**
   - Birbaşa mümkün deyil: `canModify` yalnız DRAFT və SCHEDULED üçün doğrudur (`CM/.../Exam.java:113`).
   - Amma reschedule ACTIVE, COMPLETED və ARCHIVED imtahanı başlanğıcı gələcəkdədirsə SCHEDULED-ə qaytarır (`ExamService.java:62-63, 415`). Bundan sonra `updateExam` bütün sualları sərt silib yenidən yaradır (`:142-144, 720-724`).
   - Cavab varsa, `user_answers.question_id` FK silməni bloklayır: güman ki 400 (`GlobalExceptionHandler.java:196-202`). Bu test edilməyib.
   - Cavab yoxdursa, suallar dəyişir. Saxlanmış ballar köhnə qalır, regrade yoxdur.
   - **P0:** kilid statusdan yox, "sessiya varmı" sualından asılı olmalıdır. Regrade lazımdır.
6. **İmtahan zamanı API cavabında düzgün cavab, izahat və ya bal sızırmı: sızmır.**
   - `toParticipantResponse` `isCorrect` və `explanation`-ı gizlədir (`ExamMapper.java:174-187, 276-284`).
   - Mətn suallarında variantlar ümumiyyətlə göndərilmir (`ExamService.java:849-851`).
   - `GET /api/exams/{id}` iştirakçı görünüşüdür (`:215`).
   - Review yalnız idarə edənə açıqdır (`ExamResultsService.java:246-253`).
   - Sual balı (`points`) göstərilir. Bu normaldır, Moodle da göstərir.
7. **Media imtahandan əvvəl ictimai linklə açılırmı: açılmır.**
   - Bucket privatdır.
   - URL 5 dəqiqəlik presigned-dir və yalnız iştirakçının öz işləyən sessiyasında verilir (`ExamController.java:205-209`).
   - maxPlays atomik sayılır (`MediaLinkRepository.java:180-184`).
   - Qalıq risk: URL 5 dəqiqə ərzində başqasına ötürülə bilər. **P2.**
8. **Bir şəxs neçə hesab aça bilər: məhdudiyyət yoxdur.**
   - Kimlik yalnız e-poçtdur. Telefon istəyə bağlıdır və unikal deyil (auth V1). FİN yoxdur.
   - Cəhd limiti hesab başına sayılır, ona görə yeni e-poçtla aşılır.
   - **P1:** yüksək riskli imtahanlar üçün kimlik sahəsi və ya qrup üzvlüyü (dəvət ilə) lazımdır.
9. **INSTRUCTOR rolunu kim verir:** yalnız SQL.
   - `RegisterRequest`-də rol sahəsi yoxdur, ona görə iştirakçı özü seçə bilmir.
   - `AdminBootstrap` ilk ADMIN-i yaradır.
   - Admin API-də rol dəyişmə endpointi yoxdur. **P1.**
10. **Silinmə nəyə təsir edir:**
    - İmtahan yalnız sessiyası yoxdursa silinir (`deleteIfNoSessions`, `ExamService.java:158-184`). Silinmə `EXAM_DELETED` audit sətri qoyur. Sessiyası olan imtahan arxivlənir, silinmir.
    - İstifadəçilər silinmir: təsdiqlənməmiş hesablar deaktiv olur (`AS/service/AccountCleanupJob.java`). Silmə endpointi yoxdur.
    - Qruplar soft delete olunur.
    - Media DELETED statusu alır, fayl storage-də qalır.
    - Update zamanı köhnə suallar izsiz silinir. **P2.**
11. **"Finalize" siyasəti yoxdur.** Owner və ya ADMIN qiyməti istənilən vaxt dəyişə bilər (versiya və audit: `GradingService.java:159-186`). İştirakçıya xəbər verilmir. **P2.**
12. **Vaxt qurşağı:**
    - Bütün vaxtlar UTC saxlanır (`ES/config/UtcJsonConfig.java`).
    - Gündəlik limit `EXAM_DAY_ZONE` ilə hesablanır, standart Asia/Baku (`ES/service/ExamDayClock.java`). Xarici iştirakçı üçün "gün" Bakı günüdür. `nextAttemptAt` UTC-də qaytarılır.
    - **P2:** UI-da "Bakı vaxtı ilə" qeydi olmalıdır.
13. **Rate-limit, CAPTCHA və bot müdafiəsi:**
    - CAPTCHA yoxdur.
    - Gateway limiti IP başınadır: qeydiyyat 500/s, burst 1000 (`docker-compose.public.yml:96-99`). OTP: saniyədə 1 token, sorğu 5 token aparır, burst 50 (`api-gateway application.yml:85-89`).
    - Bir e-poçta saatda 5 kod göndərilir. Bu, atomikdir: `AS/service/OtpService.java:105-127`.
    - Login: 5 səhvdən sonra 15 dəqiqəlik kilid.
    - Botun hər yeni e-poçtu yeni məktub deməkdir, məktublar isə Gmail SMTP ilə gedir. 100 000 hədəfi üçün kifayət deyil. **P0.**
14. **Konkurentlik (iki admin eyni anda):**
    - `@Version` Exam və ExamSession-da var (`Exam.java:92`). Amma müştəri versiyanı göndərmir. Update təzə oxunmuş sətri saxlayır, ona görə sonuncu yazan qalib gəlir.
    - Reschedule sətri kilidləyir (`ES/repository/ExamRepository.java:146`).
    - Grading-də `expectedVersion` var.
    - Qruplar yoxlanmayıb. **P2.**
15. **"Oxu, dəyiş, yaz" yarışları:**
    - Cəhd sayı və gündəlik limit: advisory lock və tək INSERT…SELECT ilə qorunur (`ExamAttemptRepository.java:35-70`). VAR.
    - Join linkinin `maxUses` limiti atomikdir: verify-b5-join-də 10 paralel cəhd yoxlanılıb.
    - Media plays atomikdir.
    - Bulk-add idempotentdir (verify-b6-parallel).
    - OTP sayğacı atomikdir.
    - Yarış tapılmadı.

---

## 4. Təklif olunan paketlər

Hər paket ≤ 1 həftədir və ayrıca Copilot prompt-u ola bilər. Ardıcıllıq yuxarıdan aşağıya doğrudur.

### P0 paketi (ictimai istifadədən əvvəl)

| # | Paket | Backend | Frontend | Həcm | Asılılıq |
|---|---|---|---|---|---|
| P0-1 | Qeydiyyat və OTP-nin bot müdafiəsi | Register, resend-otp və forgot-password üçün Cloudflare Turnstile yoxlaması; gündəlik qlobal və IP başına OTP göndərmə limiti; Gmail əvəzinə tranzaksiya e-poçt provayderi (SES, Mailgun və ya Postmark) | Turnstile widget | M | – |
| P0-2 | Nəticə bütövlüyü | Sessiyası olan imtahanda sual və açar dəyişikliyi qadağandır (kilid status üzrə deyil, sessiyanın olub-olmaması üzrə); reschedule redaktəni açmamalıdır; sualların sərt silinməsi əvəzinə soft delete; audit | Redaktə düyməsi bu qaydaya görə | S–M | – |
| P0-3 | Sessiyanın cihaza bağlanması | Start-da cihaz tokeni; cavab və təhvil tokeni tələb edir; ikinci cihaz "takeover" edir və fraud event yazılır; IP və UA serverdən götürülür | Token saxlanır və göndərilir; "başqa cihazda açıldı" ekranı | M | – |
| P0-4 | Profil backend-i | `GET /auth/me`, `PUT /auth/me` (ad, soyad, telefon), `POST /auth/change-password` (köhnə parol tələb olunur, digər refresh tokenlər ləğv edilir) | Profile.tsx-i bunlara bağlamaq | S–M | – |
| P0-5 | Regrade MVP | Açar dəyişəndə və ya sual ləğv ediləndə bütün sessiyaların yenidən hesablanması (`recalculateSession` artıq var); bonus bal; audit | Nəticələr səhifəsində "yenidən hesabla" | M | P0-2 |

### P1 paketi

| # | Paket | Backend | Frontend | Həcm | Asılılıq |
|---|---|---|---|---|---|
| P1-1 | Rol və istifadəçi idarəsi | Rol dəyişmə (ADMIN), admin tərəfindən bloklama və açma, auditlə | Participants səhifəsində əməliyyatlar | S–M | – |
| P1-2 | Admin təhlükəsizliyi | ADMIN və INSTRUCTOR üçün TOTP 2FA; sessiya siyahısı və ləğvi | 2FA quraşdırma və giriş ekranı | M | P1-1 |
| P1-3 | Override-lar | İstifadəçi və qrup üzrə vaxt uzatma, əlavə cəhd, fərdi bağlanma vaxtı; grace | İmtahan ayarlarında override cədvəli | M | – |
| P1-4 | Proctor əməliyyatları | Sessiyaya canlı vaxt əlavə etmək, məcburi təhvil, mesaj göndərmək (polling yetər, websocket şərt deyil); müşahidəçi rolu | LiveMonitoring-də düymələr; iştirakçıda mesaj banneri | M | P1-3 |
| P1-5 | İştirakçının review-u | Review seçimləri (cavab, düzgün cavab, izahat, rəy; "dərhal", "bağlandıqdan sonra"), nəticənin vaxtla dərci | Nəticə səhifəsində review | M | – |
| P1-6 | Bal seçimləri | Mənfi bal, çoxlu seçimdə qismən bal, imtahan üzrə bal metodu (ən yüksək, son, orta) | Ayarlar | M | P0-5 |
| P1-7a | Sual bankı: əsas | Bank cədvəlləri (kateqoriya, teq), CRUD, bankdan imtahana kopyalama, imtahanı kopyalamaq | Bank səhifəsi və imtahanda "bankdan əlavə et" | L (1 həftə) | P0-2 |
| P1-7b | Bankdan təsadüfi seçim | Kateqoriyadan N sual; hər sessiya üçün seçim DB-də saxlanır (Redis itsə də sıra dəyişmir) | Ayar | M | P1-7a |
| P1-8 | Sualların importu və exportu | CSV və Aiken (sadə), sonra GIFT | Import paneli | M | P1-7a |
| P1-9 | Bildirişlər | E-poçt: xatırlatma (T−24 saat), qrup qərarı, "nəticə hazırdır"; bildiriş cədvəli | Bildiriş mərkəzini backend-ə bağlamaq | M | P0-1 (provayder) |
| P1-10 | Yük və start paylanması | 2000+ eyni anda start üçün yük testi; lazım olsa gözləmə otağı və ya jitter | Gözləmə ekranı | M | – |
| P1-11 | Əməliyyat | Xəbərdarlıq (alert), staging compose, stub servislərin çıxarılması | – | M | – |

### P2 siyahısı
Bunlar ayrıca kiçik paketlər ola bilər:
- Matching, ordering və numerical sual növləri.
- Qiymət aralıqları və ümumi rəy.
- XLSX və PDF export, sertifikat (PDF), persentil, KR-20.
- Apellyasiya axını.
- Parol və IP məhdudiyyəti, cəhdlər arası fasilə, əvvəlki imtahandan keçmə şərti.
- Ardıcıl naviqasiya rejimi, bayrağın serverdə saxlanması.
- Sınaq imtahanı və sistem yoxlaması, təqvim.
- GDPR export və silmə.
- Qeydiyyatda əlavə sahələr (şəhər, məktəb, FİN), sayt ayarları (ad, loqo, şablonlar).
- Çox səviyyəli qruplar və avtomatik qaydalar.
- Bir neçə yoxlayan və rubrika.
- İstifadəçilərin toplu importu və exportu.
- Safe Exam Browser.

---

## 5. Moodle-dan götürmək mənasız olanlar

- Kurs, gradebook kateqoriyaları, forum, resurs, wiki, SCORM, LTI.
- Moodle-un "question behaviours" rejimləri (interactive, adaptive, CBM).
- Calculated multi və embedded answers (Cloze) sual növləri.
- Kurs səviyyəsində cohort və enrolment pluginləri. Bizim qruplar və join linkləri bu ehtiyacı qarşılayır.
- Badges və competencies.
- Plagiat pluginləri.
- Kamera və ekran proctoring-i (ProctorU, Mettl): tam versiya L+ həcmdədir və hüquqi razılıq tələb edir. Mövcud fraud event-lər və risk balı ilk mərhələ üçün yetərlidir.

---

## 6. Təsdiq edilməyənlər (test lazımdır)

1. Yük altında start: minlərlə eyni anda start. Advisory lock, sessiya üçün sualların hazırlanması və Redis keşi altında gecikmə ölçülməyib.
2. Gmail-in gündəlik göndərmə limiti: adi hesab üçün təxminən 500 alıcı. Bu, Google-un sənədlərindən götürülüb, real hesabda yoxlanmayıb.
3. Reschedule-dan sonra cavabı olan imtahanın update-i. FK-nin 400 verdiyi güman edilir, işlədilməyib (F5).
4. Redis itəndə sessiyanın sual sırası yenidən qarışır (`ExamService.java:799-802`). UX təsiri yoxlanmayıb.
5. Frontend-də naviqasiya paneli, avtosaxlama göstəricisi, mobil görünüş və ekran oxuyucu ilə əlçatanlıq: brauzerdə yoxlanmayıb.
6. Qrupların redaktəsində paralel dəyişiklik (optimistic locking) yoxlanmayıb.
7. Scheduler 3 instansiyada işləyəndə: əməliyyatlar şərtli UPDATE ilə qorunur, paylanmış kilid yoxdur. Yük altında təkrar iş yoxlanmayıb.
8. Cloudflare tunelinin arxasında start-da saxlanan IP: o, müştərinin gövdəsindən gəlir və real IP olmaya bilər.
9. `websocket-service` və `monitoring-service`-in production-da heç bir funksiya daşımadığı yalnız kodu oxumaqla müəyyən edilib.
