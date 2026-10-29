# DEMO ödəniş rejimi

Bank inteqrasiyası hazır olana qədər ödənişli imtahanların bütün yolunu canlı saytda göstərmək üçündür:
kataloq → e-poçt kodu → "Ödə" → demo ödəniş səhifəsi → giriş → imtahan → nəticə.

**Real pul tutulmur.** Demo ödəniş səhifəsində kart məlumatı soruşulmur. "Uğurlu ödəniş (demo)" düyməsi sifarişi
ödənilmiş sayır, amma heç bir hesabdan pul çıxmır.

## Necə açılır

`F:\exam-system\.env`-də bu dörd sətir olmalıdır:

```
PAID_ACCESS_ENABLED=true
PAYMENTS_DEMO_MODE=true
PAYMENTS_PROVIDER=SANDBOX
PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true
```

Sonra (iştirakçı olmayan vaxt):

```
cd F:\exam-system
.\scripts\go-public.ps1 start
```

`start` sarı yazı göstərir: "DEMO odenis rejimi aciqdir: real pul tutulmur". Bu normaldır, dayanmır.

**Niyə `_PUBLIC`?** İctimai rejim (`docker-compose.public.yml`) development-in `PAYMENTS_SANDBOX_ALLOWED` açarını
götürmür, öz açarı `PAYMENTS_SANDBOX_ALLOWED_PUBLIC`-dir. `PAYMENTS_SANDBOX_ALLOWED=true` olsa da ola bilər, amma
ictimai rejim üçün həlledici olan `_PUBLIC`-dir.

**Üç açardan biri çatmasa** demo işləmir:

- sandbox bağlı qalır, "Ödə" addımında iştirakçı "ödəniş mümkün deyil" görür;
- `go-public.ps1 start` dayanır və nəyin çatmadığını yazır;
- exam-service log-unda ERROR olur.

## Necə yoxlanır

| Yoxlama | Demo açıq olanda |
|---|---|
| `.\scripts\go-public.ps1 status` | `Payments: DEMO`, `Sandbox: DEMO` (sarı) |
| `<ünvan>/api/guest/feature` | `"paidAccessEnabled":true,"paymentsDemo":true` |
| `<ünvan>/api/public/paid-exams` | hər imtahanda və səhifədə `"paymentsDemo":true` |
| `docker exec exam-exam-service wget -q -O - http://127.0.0.1:8082/actuator/health/payments` | `"status":"DEMO"` (ümumi health UP qalır) |
| exam-service log-u | hər başlanğıcda WARN: `Payments DEMO mode is ON: no real money is charged` |
| Admin → Audit | hər başlanğıcda `PAYMENTS_DEMO_MODE_ON` sətri |

## Necə söndürülür

`.env`-də:

```
PAYMENTS_DEMO_MODE=false
PAYMENTS_SANDBOX_ALLOWED_PUBLIC=false
```

Sonra `.\scripts\go-public.ps1 start`.

- Ödənişli bölmə saytda görünməyə davam edir (`PAID_ACCESS_ENABLED=true`).
- "Ödə" addımında iştirakçı "ödəniş mümkün deyil" görür.
- Demo vaxtı ödənilmiş sifarişlər və onların verdiyi girişlər **silinmir**: olduğu kimi qalır, admin onları DEMO nişanı
  ilə görür.

## Demo zamanı nəyə diqqət etmək lazımdır

1. **Real dəyəri olan ödənişli imtahan dərc etməyin.** Demo-da hər kəs "Uğurlu ödəniş (demo)" düyməsi ilə imtahana
   pulsuz girə bilər. Real sertifikat, real mükafat və ya real qiymətli imtahan demo vaxtı ödənişli olmamalıdır.
2. **Demo sifarişlər gəlir deyil.** Admin xülasəsində "real gəlir" yalnız real sifarişlərdir, demo sifarişlər ayrıca
   göstərilir (`demoPaidCount`, `demoAmountMinor`). CSV-də "Demo" sütunu `bəli`/`xeyr` yazır.
3. **Qəbz məktubunda** mövzu və ilk sətir "DEMO — real ödəniş alınmayıb" deyir.
4. **Nağd və ya köçürmə ilə ödəniş.** Admin demo sifarişi "ödənildi" (mark-paid) kimi qeyd edərsə, sifariş MANUAL olur,
   yəni real pul sayılır.
5. **Avtomatik bərpa.** `watch-public.ps1` saytı demo rejimdə də yenidən qaldırır: `start` demo-da dayanmır.

## Real provayder qoşulanda

1. `.env`-də:
   ```
   PAYMENTS_DEMO_MODE=false
   PAYMENTS_SANDBOX_ALLOWED=false
   PAYMENTS_SANDBOX_ALLOWED_PUBLIC=false
   PAYMENTS_PROVIDER=<real provayderin adı>
   ```
   Provayderin açarları da (`PAYMENTS_EXTERNAL_*`, `.env.example`) yalnız `.env`-də olmalıdır.
2. `.\scripts\go-public.ps1 start`, sonra `status`: `Payments: EXTERNAL`.
3. Bir kiçik real ödəniş ilə bütün yolu yoxlayın.

Köhnə `PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND` açarı geriyə uyğunluq üçün qalıb: sandbox-u eyni cür açır,
amma hər başlanğıcda ERROR yazır və `go-public.ps1 start` onu qəbul etmir. Demo üçün `PAYMENTS_DEMO_MODE` istifadə edin.
