# CINESTREAM PRO — PHASE 37
## FlixQuest parity: Season credits, season videos, trailer layout, More Like This pagination

**تاريخ التنفيذ:** 31 أكتوبر 2026  
**قاعدة العمل:** `CINESTREAM-PRO-PHASE36.1-KOTLIN-BUILD-FIX.zip`  
**المشروع المرجعي الذي تمت مراجعته:** `flixquest-main.zip`

---

## 1. مراجعة FlixQuest قبل التعديل

تم فحص ملفات المصدر الفعلية ذات الصلة في أرشيف FlixQuest، وليس الاعتماد على الصور وحدها:

| ملف FlixQuest | ما تم التحقق منه |
|---|---|
| `lib/catalog/season_source.dart` | مصدر منفصل لبيانات الموسم: الحلقات، الاعتمادات، الصور، والفيديوهات. فشل أحد الطلبات لا يمنع عرض بقية أجزاء الصفحة. |
| `lib/mobile/screens/season_screen.dart` | ترتيب صفحة الموسم: الملخص والحلقات ثم Cast مع See All ثم Videos ثم Posters. |
| `lib/mobile/screens/credits_screen.dart` | شاشة مستقلة لعرض طاقم الموسم، مع التبديل بين Cast وCrew وتجميع الفريق حسب القسم. |
| `lib/models/credits.dart` | بنية بيانات الاعتمادات المجمّعة وأدوار الممثلين والوظائف وعدد الحلقات. |
| `lib/mobile/widgets/details_parts.dart` | `VideoTile` بنسبة 16:9، صورة YouTube مع زر تشغيل في المنتصف، ثم عنوان الفيديو أسفل الصورة. |
| `lib/mobile/screens/title_details_screen.dart` | تبويب Trailers & More يعرض بطاقات الفيديو عموديًا، وMore Like This يضيف صفحات أخرى بدل إبقاء عدد ثابت من العناصر. |
| `lib/catalog/details_play.dart` | قواعد فلترة الفيديوهات الصالحة على YouTube ومنع تكرار المعرفات، مع اختيار Trailer ثم Teaser ثم فيديو صالح آخر. |

### خلاصة السلوك المرجعي

- بيانات الموسم مستقلة عن بيانات المسلسل؛ لا يكفي استخدام طاقم المسلسل العام بدل طاقم الموسم.
- `See All` يفتح شاشة Cast And Crew مستقلة، ولا يكتفي بتوسيع الصف الأفقي.
- بيانات الفريق مجمعة حسب department، ولكل عضو job/role وعدد الحلقات عند توفره.
- فيديوهات الموسم تعرض ضمن صفحة الموسم بين Cast وPosters.
- VideoTile في FlixQuest عبارة عن صورة عريضة 16:9 مع تشغيل وعنوان أسفلها.
- زر Show more في More Like This يطلب صفحة إضافية ويضيف النتائج الجديدة دون تكرار العناصر السابقة.

---

## 2. التعديلات المنفذة في CineStream Pro

### 2.1 بيانات الموسم من TMDB

تم توسيع طلب تفاصيل الموسم ليستخدم:

`append_to_response=aggregate_credits,videos,images`

وتم إضافة DTOs خاصة ببنية `aggregate_credits` بدل إعادة تفسيرها على أنها Credits عادية:

- `TmdbAggregateCredits`
- `TmdbAggregateCast` و`TmdbAggregateRole`
- `TmdbAggregateCrew` و`TmdbAggregateJob`

وتم توسيع نموذج المجال:

- إضافة `CrewMember` مع department وjobs والصورة وعدد الحلقات.
- إضافة `episodeCount` إلى `CastMember`.
- إضافة `crew` إلى `Season`.
- الإبقاء على طاقم الموسم كاملًا دون الاقتصار على أول 30 ممثلًا.
- تحميل فيديوهات YouTube للموسم دون تقييدها بنوع Trailer فقط.

### 2.2 صفحة About this season

أصبح تسلسل محتوى الموسم:

1. ملخص الموسم والتحكم في اختيار الموسم.
2. الحلقات.
3. Cast مع زر See All.
4. Videos عند توفر فيديوهات للموسم.
5. Posters ثم Backdrops.

بطاقات فيديو الموسم تستخدم صور YouTube المصغرة بنسبة 16:9 وزر تشغيل. الضغط عليها يفتح مشغل YouTube المضمّن داخل صفحة الموسم.

### 2.3 شاشة Cast And Crew

تم إنشاء شاشة Compose مستقلة داخل تجربة تفاصيل الموسم، وتتضمن:

- شريط رجوع وعنوان العمل والموسم.
- شريط تبويب Cast / Crew عندما تتوفر المجموعتان.
- قائمة الممثلين مع الشخصية وعدد الحلقات عند توفره.
- قائمة الفريق مجمعة بحسب Department، مع الوظائف وعدد الحلقات.
- فتح صفحة الشخص من خلال callback التنقل الحالي `onPersonClick`.
- الرجوع إلى About this season دون فقدان اختيار الموسم.

### 2.4 Trailers & More

تم تغيير التخطيط من بطاقات أفقية صغيرة/قابلة للطي إلى قائمة عمودية كاملة العرض:

- صورة فيديو بنسبة 16:9.
- زر تشغيل فوق الصورة.
- عنوان الفيديو أسفلها.
- إظهار جميع فيديوهات YouTube المسترجعة، لا Trailer فقط.
- الاحتفاظ بفحص الاتصال بالإنترنت قبل تشغيل الفيديو.
- عرض حالة تحميل عند جلب الفيديوهات، ورسالة عدم توفر عند انتهاء الطلب دون نتائج.

أصبحت قائمة الفيديوهات تقبل Trailer وTeaser وFeaturette وغيرها من أنواع YouTube عندما يوردها مصدر البيانات، مع إزالة المفاتيح غير الصالحة والتكرارات.

### 2.5 More Like This — Show more

تمت إضافة Pagination لكل من:

- الأفلام.
- المسلسلات.
- الأنمي عبر مسار المسلسلات المقيّد بفلتر الأنمي.
- أفلام الأنمي مع الحفاظ على فلتر اللغة اليابانية المستخدم حاليًا.

زر Show more يطلب الصفحة التالية من TMDB Discover بنفس قيود الأنواع، ويضيف النتائج الجديدة دون تكرارها، ويظهر مؤشر تحميل، ويتوقف عندما لا تبقى صفحات أخرى.

### 2.6 التخزين المحلي

تم تحديث `MediaDetailsCacheManager` ليحفظ ويقرأ:

- `Season.crew`.
- وظائف أعضاء الفريق.
- `episodeCount` للممثلين والفريق.

الحقول غير الموجودة في ملفات الكاش الأقدم تُقرأ بقيم افتراضية آمنة.

---

## 3. الملفات الرئيسية التي تم تعديلها

- `app/src/main/java/com/example/domain/models/Media.kt`
- `app/src/main/java/com/example/data/remote/TmdbModels.kt`
- `app/src/main/java/com/example/data/remote/TmdbApiService.kt`
- `app/src/main/java/com/example/data/repository/TmdbMediaRepositoryImpl.kt`
- `app/src/main/java/com/example/ui/screens/details/DetailsScreens.kt`
- `app/src/main/java/com/example/ui/screens/details/MovieDetailsViewModel.kt`
- `app/src/main/java/com/example/ui/screens/details/SeriesDetailsViewModel.kt`
- `app/src/main/java/com/example/utils/MediaDetailsCacheManager.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ar/strings.xml`

---

## 4. التحقق والقيود

### فحوصات اكتملت

- التحقق من سلامة أرشيف FlixQuest وقراءة ملفات المصدر المرجعية أعلاه.
- التحقق من سلامة بنية XML لملفي strings الإنجليزية والعربية.
- التحقق من عدم تكرار أسماء موارد strings في الملفين.
- فحص مراجع استدعاء شاشة الموسم، شاشة الاعتمادات، فيديوهات الموسم، وقوائم More Like This.
- فحص مصدر Kotlin بحثًا عن أخطاء تركيبية صريحة؛ لم تظهر رسائل parser من نوع `expecting` أو `unexpected tokens` في هذا الفحص المعزول.

### لم يكتمل

لم يكتمل بناء APK محليًا. محاولة تشغيل:

`./gradlew --no-daemon --stacktrace :app:compileDebugKotlin`

توقفت قبل بدء Gradle بسبب عدم قدرة البيئة على حل اسم المضيف `services.gradle.org` أثناء تنزيل Gradle 8.9، وظهر:

`java.net.UnknownHostException: services.gradle.org`

لذلك لا يُسجل هذا التسليم على أنه Build PASS. كما أن فحص `kotlinc` المعزول دون classpath الخاص بمشروع Android أنتج أخطاء مراجع غير محلولة للمكتبات الخارجية، ولا يعتبر بديلًا عن تجميع Gradle الحقيقي.

**الخطوة المطلوبة بعد التسليم:** رفع هذه النسخة وتشغيل GitHub Actions `assembleDebug`. أي خطأ جديد في Kotlin يجب إصلاحه بناءً على سجل البناء الفعلي قبل اعتماد PHASE 37 نهائيًا.

---

## 5. مراجع TMDB

تدعم واجهة تفاصيل الموسم `append_to_response`، كما تدعم موارد الموسم المجمعة مثل `aggregate_credits` و`videos` و`images` وفق توثيق TMDB الرسمي:

- https://developer.themoviedb.org/reference/tv-season-details
- https://developer.themoviedb.org/docs/append-to-response

