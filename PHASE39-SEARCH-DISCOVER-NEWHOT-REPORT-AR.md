# PHASE 39 — تنفيذ Search وDiscover وNew & Hot وفق FlixQuest

**المشروع:** CINESTREAM PRO  
**تاريخ إعادة التسليم:** 31 أكتوبر 2026  
**الحالة:** التعديلات البرمجية موجودة داخل ملفات المشروع؛ الفحوصات المصدرية ناجحة؛ بناء APK غير مؤكد بسبب تعذر تنزيل Gradle 8.9.

## 1. تدقيق FlixQuest قبل التنفيذ

تمت مراجعة الأرشيف `flixquest-main.zip` مباشرة، مع فحص ملفات الواجهات والكتالوج والاختبارات ذات الصلة:

- `lib/mobile/screens/search_screen.dart` — تنفيذ `_GenreTiles` وتخطيط شبكة التصنيفات.
- `lib/mobile/screens/discover_screen.dart` — تنفيذ `_ServiceGrid` و`_ServiceTile` وتفاعل اختيار خدمة البث.
- `lib/catalog/discover_query.dart` — ترتيب خدمات البث ومعرّفات TMDB.
- `lib/mobile/screens/new_and_hot_screen.dart` — تنفيذ `_PremiereGroupCard` و`_PremiereCard` و`_HotCardLayout` و`_Backdrop` و`_HotTitle`.
- `lib/catalog/new_and_hot.dart` و`lib/catalog/title_logos.dart` — بيانات القادم وطريقة اختيار شعار العمل.
- اختبارات البحث والاكتشاف وNew & Hot ذات الصلة.

ثم تمت مقارنة التنفيذ بنسخة CINESTREAM PRO السابقة، وأُعيد تطبيق التغييرات على نسخة نظيفة من أرشيف المرحلة 38. التغييرات أدناه موجودة في شيفرة Kotlin الفعلية وليست وصفًا مخططًا فقط.

## 2. Search — Movie genres / Series genres / Anime genres

**الملف المعدل:** `app/src/main/java/com/example/ui/screens/search/SearchScreen.kt`

- استبدال البطاقات الطويلة ذات ارتفاع `72dp` ببطاقات تصنيف مدمجة بارتفاع `52dp`.
- عمودان على الهاتف وأربعة على الشاشات اللوحية.
- تباعد أفقي ورأسي `10dp`.
- حشوة نصية أفقية `14dp`، مع سطر واحد واقتطاع آمن للنص الطويل.
- الإبقاء على أقسام الأفلام والمسلسلات والأنمي الثلاثة.
- الإبقاء على التنقل الحالي الذي يمرر نوع المحتوى ومعرّف التصنيف إلى Discover؛ لم يتم استبدال معرّفات التصنيفات أو ربطها بقيم شكلية.

## 3. Discover — Streaming On

**الملف المعدل:** `app/src/main/java/com/example/ui/screens/home/DiscoverScreen.kt`

- ثلاثة أعمدة على الهاتف وخمسة على الأجهزة اللوحية.
- عرض كل بطاقة مشتق من عرض العمود، وارتفاعها `58%` من عرضها بدل الارتفاع الثابت السابق.
- لوحة خلفية فاتحة `#F2F2F3` للشعار، مع `ContentScale.Fit` حتى لا يتم قص الشعارات.
- الحفاظ على ترتيب خدمات FlixQuest ومعرّفات TMDB نفسها:
  `Netflix 8`، `Prime Video 9`، `Disney+ 337`، `Hulu 15`، `Max 384`، `Apple TV+ 350`، `Peacock 387`، `iTunes 2`، `YouTube 188`، `Paramount+ 531`، `Netflix Kids 175`.
- يبقى اختيار الخدمة مرتبطًا بـ`filters.providers` وبمعرّف المزود الحقيقي؛ وتظهر علامة الاختيار عند التفعيل، مع شارة KIDS للمزوّد المناسب.

**ملاحظة حول الشعارات:** قد تبدو بعض شعارات الصور المرجعية كأنها NBC أو Apple Music. لكن كتالوج FlixQuest المصدر يسمي هذين العنصرين Peacock وiTunes. حافظ التنفيذ على الاسم والمعرّف الحقيقيين بدل ربط شعار ظاهر بمعرّف مزوّد مختلف قد ينتج عنه فلترة خاطئة.

## 4. New & Hot — Coming Soon

**الملف المعدل:** `app/src/main/java/com/example/ui/screens/home/NewAndHotScreen.kt`

- تجميع العناصر حسب تاريخ الإصدار/العرض.
- وضع الشهر واليوم في عمود جانبي بعرض `68dp`، وبطاقة العمل في المساحة المجاورة.
- صورة خلفية بنسبة `16:9`.
- جلب شعار العمل من صور TMDB عند توفره، مع الرجوع إلى عنوان نصي مقروء عند عدم توفر شعار صالح.
- اختيار الشعار باللغة الحالية أولًا، ثم الإنجليزية، ثم الشعار غير المرتبط بلغة، مع استبعاد SVG وتفضيل الشعارات العريضة.
- إضافة موعد العرض بلون التمييز، ثم نوع العمل والتصنيفات، ثم وصف مختصر بسطرين على الهاتف وأربعة على العرض الواسع.
- إظهار My List، وعدم إظهار زر Play داخل بطاقة Coming Soon.
- الحفاظ على الأنمي كنوع مستقل في Upcoming، وعرض تصنيفاته عبر مسار الأنمي بدل معاملته كفيلم أو مسلسل عادي.
- الحفاظ على أقسام New & Hot الأخرى، ومنها Everyone’s Watching وTop 10 Anime.

تستخدم عملية جلب الشعار `RetrofitClient` الحالي ونداءات التفاصيل الحالية مع `append_to_response=images`؛ لم تتم إضافة مكتبة جديدة ولم يتم إنشاء عميل HTTP ثانٍ.

## 5. الموارد والتغييرات الفعلية

تمت إضافة مفاتيح التواريخ في ملفي اللغة:

- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ar/strings.xml`

وتوجد الشيفرة المعدلة مباشرة في الملفات الثلاثة:

- `SearchScreen.kt`
- `DiscoverScreen.kt`
- `NewAndHotScreen.kt`

أُرفق داخل المشروع ملف `PHASE39-SEARCH-DISCOVER-NEWHOT-SOURCE.patch` لتوضيح فرق الشيفرة وإعادة تطبيقه عند الحاجة.

## 6. نتائج التحقق

- فحص XML: اجتازت ملفات الموارد الـ13 عملية التحليل دون أخطاء.
- أسماء الموارد في `values` و`values-ar`: لا توجد أسماء مكررة.
- مفاتيح Coming/Premieres موجودة في اللغتين وبها placeholder صحيح.
- ترتيب مزوّدي البث ومعرّفاتهم: مطابق لكتالوج FlixQuest.
- فحوصات عقود الشيفرة والتخطيط: `20/20 PASS`.
- فحص Kotlin parser-only: صفر تشخيصات نحوية من نوع `expecting` أو `unexpected tokens`. أداة `kotlinc` استُخدمت بلا classpath الخاص بـAndroidX/Compose، ولذلك ظهرت مراجع غير محلولة؛ لا يُعد ذلك فحص أنواع أو بناءً ناجحًا.

### حالة Gradle وAPK

تمت محاولة `./gradlew --offline --no-daemon assembleDebug`، لكن Gradle Wrapper حاول تنزيل `gradle-8.9-bin.zip` وفشل بسبب:

`java.net.UnknownHostException: services.gradle.org`

وعليه فإن `assembleDebug` **غير متحقق** في بيئة التنفيذ الحالية. لا أذكر أن APK بُني بنجاح، ولا أعتبر فحوصات XML أو الشيفرة الثابتة بديلًا عن بناء Android كامل.
