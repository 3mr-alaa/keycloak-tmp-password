# Keycloak Temporary Password via SMS

إضافة لـ Keycloak 26 بتحط زرار **Generate tmp password** في صفحة اليوزر في الـ Admin Console.
لما الأدمن يدوس عليه:

1. بيتعمل باسورد عشوائي (من غير حروف متشابهة زي `0/O` و`1/l`) ومتوافق مع الـ Password Policy بتاعة الـ realm.
2. الباسورد بيتحفظ لليوزر كـ **temporary**، يعني هيُطلب منه يغيّره أول ما يعمل login (Required action: `UPDATE_PASSWORD`).
3. الباسورد بيتبعت SMS على رقم اليوزر (attribute `phoneNumber`).
4. الباسورد **بينتهي بعد ساعة** (قابلة للتغيير): لو اليوزر ما استخدموش وغيّره خلال الساعة، بيتمسح ومش هيقدر يدخل بيه، والأدمن يبعتله واحد جديد.
5. لو الـ SMS فشل، تغيير الباسورد بيتلغي (rollback) والباسورد القديم بيفضل شغال.
6. بيتسجل Admin Event (`ACTION` على `users/{id}/tmp-password`) لو Admin events متفعلة.

الباسورد عمره ما بيرجع في الـ response ولا بيظهر للأدمن.

## المكونات

| الجزء | الوصف |
|---|---|
| `TmpPasswordExpiry` | Scheduled task بيمسح الباسوردات المؤقتة اللي انتهت |
| `TmpPasswordResource` | REST endpoint: `POST /realms/{realm}/tmp-password/users/{userId}` |
| `HttpSmsSender` | بيبعت SMS لأي HTTP API (URL / method / body template / header قابلين للتهيئة) |
| Theme `tmp-password-admin` | Admin theme مبني على `keycloak.v2` بيضيف الزرار |

### الصلاحيات
الـ endpoint بيستخدم نفس الـ token بتاع الـ Admin Console ونفس فحص الصلاحيات بتاع Keycloak:
لازم الأدمن يكون عنده `manage-users` على الـ realm (أو صلاحية manage على اليوزر ده لو Fine-Grained Admin Permissions شغالة).
الـ token لازم يكون من نفس الـ realm أو من الـ `master`.

## البناء

```bash
mvn package
```

الناتج: `target/keycloak-tmp-password.jar`

## التركيب

1. انسخ الـ jar في فولدر `providers/`:

```bash
cp target/keycloak-tmp-password.jar /opt/keycloak/providers/
```

2. اعمل build (في الـ production mode):

```bash
/opt/keycloak/bin/kc.sh build
```

3. ابدأ Keycloak بإعدادات الـ SMS (شوف الجدول تحت).

4. فعّل الـ theme: **Realm settings → Themes → Admin theme = `tmp-password-admin`**
   على الـ realm اللي الأدمن بيعمل منه login (غالباً `master`).

5. عرّف attribute رقم التليفون: في Keycloak 26 الـ User Profile شغال دايماً، فلازم تضيف attribute اسمه `phoneNumber`
   من **Realm settings → User profile → Create attribute** (أو تغيّر `phone-attribute` لاسم الـ attribute عندك).

### Docker

```dockerfile
FROM quay.io/keycloak/keycloak:26.8.2 AS builder
COPY target/keycloak-tmp-password.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.2
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

## الإعدادات

كل الإعدادات بتتحط كـ options في Keycloak بالشكل ده:

```
--spi-realm-restapi-extension--tmp-password--<option>=<value>
```

أو كـ environment variable:

```
KC_SPI_REALM_RESTAPI_EXTENSION__TMP_PASSWORD__<OPTION>   (الـ - تبقى _)
```

| Option | Default | الوصف |
|---|---|---|
| `sms-mode` | `log` | `http` للإرسال الحقيقي. `log` بيكتب الرسالة (بالباسورد) في الـ log للتجربة فقط، **متستخدمهوش في production** |
| `sms-url` | — | URL الـ SMS API. ممكن يحتوي `{phone}` و`{message}` (بيتعملهم URL-encode) |
| `sms-method` | `POST` | HTTP method |
| `sms-content-type` | `application/json` | لو فيه `json` القيم بتتعمل JSON-escape، لو `application/x-www-form-urlencoded` بتتعمل URL-encode |
| `sms-body-template` | `{"to":"{phone}","message":"{message}"}` | الـ body اللي بيتبعت |
| `sms-header-name` | — | اسم header للـ authentication (مثلاً `Authorization`) |
| `sms-header-value` | — | قيمته (مثلاً `Bearer xxx` أو `Basic base64(user:pass)`) |
| `sms-timeout-seconds` | `10` | |
| `phone-attribute` | `phoneNumber` | اسم الـ user attribute اللي فيه رقم التليفون |
| `message-template` | `Your temporary password for {realm} is: {password}` | نص الرسالة، متاح `{password}` و`{username}` و`{realm}` و`{minutes}`. لو الانتهاء شغال الـ default بيضيف `(valid for {minutes} minutes)` |
| `password-length` | `10` | |
| `password-special-chars` | `true` | يحط رموز (`@#$%&*!?`) ولا لأ |
| `temporary` | `true` | يطلب من اليوزر يغيّر الباسورد بعد أول login |
| `expiration-minutes` | `60` | مدة صلاحية الباسورد المؤقت بالدقايق. `0` يلغي الانتهاء |
| `cleanup-interval-seconds` | `60` | كل قد إيه بيتفحص الباسوردات اللي انتهت |

> الـ placeholders بتتكتب بقوس واحد `{phone}` مش `${phone}`، لأن Keycloak نفسه بيفسّر `${...}` في الـ config.

### أمثلة

**JSON API بـ API key:**

```bash
bin/kc.sh start \
  --spi-realm-restapi-extension--tmp-password--sms-mode=http \
  --spi-realm-restapi-extension--tmp-password--sms-url=https://sms.example.com/api/send \
  --spi-realm-restapi-extension--tmp-password--sms-header-name=X-Api-Key \
  --spi-realm-restapi-extension--tmp-password--sms-header-value=YOUR_KEY \
  --spi-realm-restapi-extension--tmp-password--sms-body-template='{"to":"{phone}","text":"{message}","sender":"MyApp"}'
```

**Twilio (form + Basic auth):**

```bash
bin/kc.sh start \
  --spi-realm-restapi-extension--tmp-password--sms-mode=http \
  --spi-realm-restapi-extension--tmp-password--sms-url=https://api.twilio.com/2010-04-01/Accounts/ACxxxx/Messages.json \
  --spi-realm-restapi-extension--tmp-password--sms-content-type=application/x-www-form-urlencoded \
  --spi-realm-restapi-extension--tmp-password--sms-body-template='To={phone}&From=%2B15551234567&Body={message}' \
  --spi-realm-restapi-extension--tmp-password--sms-header-name=Authorization \
  --spi-realm-restapi-extension--tmp-password--sms-header-value='Basic BASE64_OF_SID:TOKEN'
```

حط الـ API keys في environment variables أو في `conf/keycloak.conf`، مش في سطر الأوامر في production.

## استخدام الـ API مباشرة

```bash
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://keycloak.example.com/realms/myrealm/tmp-password/users/<user-id>
```

| Status | المعنى |
|---|---|
| `200` | `{"status":"sent","phone":"**********567","temporary":true,"expiresAt":"2026-11-23T18:06:12Z","expiresInMinutes":60}` |
| `400` | اليوزر مالوش رقم تليفون، أو الباسورد مش متوافق مع الـ policy |
| `401` | مفيش token أو الـ token مش صالح |
| `403` | الأدمن مالوش صلاحية على اليوزر ده |
| `404` | اليوزر مش موجود |
| `502` | الـ SMS provider رفض الطلب أو مش reachable (الباسورد ما اتغيرش) |

## ملاحظات

- الزرار بيتضاف بـ JavaScript فوق الـ Admin Console الأصلي (`keycloak.v2`)، فبعد ترقية Keycloak جرّب إنه لسه ظاهر في صفحة اليوزر.
  الـ script بياخد الـ access token من requests الـ console نفسه.
- لو الـ Admin Console على hostname مختلف عن الـ server (`hostname-admin`)، الطلب هيبقى cross-origin ومحتاج CORS.
- Keycloak بيطبع تحذير `KC-SERVICES0047` إن الـ `realm-restapi-extension` SPI داخلي؛ ده طبيعي لكل الإضافات اللي من النوع ده.

## انتهاء الباسورد بيشتغل إزاي

لما الباسورد بيتعمل، بيتحفظ على اليوزر 3 attributes:
`tmp-password=pending`، `tmp-password-expires-at` (وقت الانتهاء)، و`tmp-password-created-at` (وقت إنشاء الـ credential).

كل `cleanup-interval-seconds` فيه task بيدور على اليوزرز اللي عليهم `tmp-password=pending`:
- لو الباسورد الحالي لسه هو المؤقت والوقت عدّى: **بيمسح الباسورد** ويشيل الـ attributes.
- لو الباسورد اتغير (اليوزر غيّره بعد الـ login، أو أدمن عمل reset): بيشيل الـ attributes بس وما بيلمسش الباسورد الجديد.

يعني الانتهاء بيحصل خلال `cleanup-interval-seconds` بعد الوقت المحدد (دقيقة بالـ default)، ومن غير ما تحتاج تعدّل أي Authentication flow.
في الـ cluster الـ task بيشتغل على كل node، وده مش مشكلة لأن العملية نفسها idempotent.

لو الـ attributes دي ظاهرة في تاب Attributes متمسحهاش، لأن من غيرها الباسورد مش هينتهي.

## الاختبار

اتجرب على Keycloak 26.8.2: الزرار في الـ Admin Console، إرسال الـ SMS لـ HTTP mock server، تسجيل الدخول بالباسورد الجديد
(بيطلب تغييره)، فحص الصلاحيات (401/403)، ورجوع الباسورد القديم لما الـ SMS يفشل،
وانتهاء الباسورد (اتمسح بعد المدة، والباسورد اللي اتغير قبل الانتهاء فضل شغال).

```bash
mvn test
```
