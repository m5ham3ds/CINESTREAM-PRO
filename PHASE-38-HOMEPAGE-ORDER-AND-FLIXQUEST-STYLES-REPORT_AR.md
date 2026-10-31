# PHASE 38 — تثبيت ترتيب الصفحة الرئيسية وأنماط الأقسام الأربعة المستوحاة من FlixQuest

**التاريخ:** 31 أكتوبر 2026  
**المشروع:** CINESTREAM-PRO  
**المصدر المرجعي:** `flixquest-main.zip` (المصدر المرفق في المحادثة)  
**نطاق التغيير:** الصفحة الرئيسية فقط؛ لا تُحذف مصادر البيانات أو صفحات Movies / Series / Anime أو وظائف التشغيل والتنزيل.

## 1. نتيجة مراجعة FlixQuest

تم فحص مسار الصفحة الرئيسية ومكوناته المرتبطة مباشرةً في المصدر:

- `lib/mobile/screens/home_screen.dart`: تركيب الصفحة، ترتيب صفوف Home، وإسناد بيانات كل نوع.
- `lib/catalog/home_feed_controller.dart`: فصل قوائم Trending Today / Trending Week / Popular / Top Rated / New Releases / Upcoming، تحميل صفوف الخدمات، وآلية اختيار التصنيفات.
- `lib/mobile/widgets/category_section.dart`: تخطيطات `feature` و`stillsAndPosters` و`twoPosterRows` و`stills` و`posters`.
- `lib/mobile/widgets/media_rows.dart`: `PosterRow` و`TopTenRow` و`ContinueRow` و`StillRow`.
- `lib/mobile/widgets/section_header.dart`: عنوان القسم، kicker الخاص بنوع المحتوى، وSee all.
- `lib/mobile/widgets/hero_card.dart` و`lib/catalog/home_hero.dart`: البانر الدوار، الإيقاف أثناء التفاعل/التمرير، تبديل الشرائح، وتفضيل مشاهدة حديثة خلال ثلاثة أيام.

FlixQuest الأصلي يختار خمس فئات عشوائية من أنواع الأفلام والمسلسلات ويمنحها تخطيطات مختلفة. هذا السلوك العشوائي لم يُنقل كما هو؛ لأن المطلوب هنا مجموعة محددة من أربعة أقسام فقط، بأنواع وبيانات ثابتة.

## 2. ترتيب الصفحة الرئيسية المعتمد

بعد البانر المتحرك، تُعرض الأقسام بهذا الترتيب دون إضافة رفوف أخرى غير مطلوبة:

1. Continue Watching
2. Top 10 Movies Today
3. Top 10 Series Today
4. Top 10 Anime Today
5. Library
6. Trending Movies
7. Trending Series
8. Trending Anime
9. New Movies
10. New Episodes
11. Popular Movies
12. Popular Series
13. Popular Anime
14. Streaming Services
15. Popular Series on Netflix
16. Popular Movies on Disney+
17. Popular Movies on Prime Video
18. Top Rated Movies
19. Top Rated Series
20. Top Rated Anime
21. Upcoming Movies
22. Action — Movies only
23. Reality — Series only
24. Mystery — Series only
25. History — Movies only

لا يُعرض أي قسم Home آخر خارج هذه القائمة. يظل شريط العلامة التجارية والفلاتر والتنقل جزءًا من واجهة التطبيق وليس رف محتوى إضافيًا. كما أن صفحات تبويب Movies / Series / Anime وشاشات التنزيلات والمكتبة لا تُحذف.

الأقسام التي لا توجد لها عناصر فعلية تُخفى بدل إنشاء صف فارغ أو محتوى وهمي.

## 3. فصل مصادر البيانات لمنع تكرار العناوين

| القسم | المصدر الفعلي في CineStream |
|---|---|
| Top 10 Movies Today | TMDB Trending Movie مع `window=day`، أول 10 عناصر |
| Top 10 Series Today | TMDB Trending TV مع `window=day`، أول 10 عناصر بعد استبعاد Anime |
| Top 10 Anime Today | نافذة Anime اليومية، مع تحقق Animation (`genre 16`) واللغة اليابانية |
| Trending Movies | TMDB Trending Movie مع `window=week` |
| Trending Series | TMDB Trending TV مع `window=week`، باستبعاد Anime |
| Trending Anime | تدفق مخصص يجمع المرشحين اليوميين والأسبوعيين، يتحقق من Anime، ويستخدم Popular Anime كـ backfill محدود إذا كان عدد المرشحين الحقيقيين أقل من 20 |
| New Movies | اكتشاف الأفلام ضمن نافذة تاريخ الإصدار الأخيرة |
| New Episodes | أحدث إصدارات TV + Anime في رف واحد |
| Popular Movies / Series | قوائم TMDB Popular المستقلة، وليست Trending |
| Popular Anime | صفحة TMDB Popular Anime مع تحقق النوع |
| Streaming Services | شعارات الخدمات المحلية، قابلة للنقر |
| Netflix / Disney+ / Prime Video | `discover/tv` أو `discover/movie` مع watch provider والمنطقة |
| Top Rated | صفحات TMDB Top Rated المستقلة لكل نوع |
| Upcoming Movies | TMDB Upcoming Movies فقط |

التمييز بين Top 10 اليومي وTrending الأسبوعي وPopular وTop Rated مهم؛ لا يستخدم الرف الواحد عنوانًا لمصدر الرف الآخر. Trending Anime له قيد خاص موضح أعلاه بسبب أن نقطة TMDB العالمية لا تتيح فلترة Anime على مستوى الخادم مباشرةً.

## 4. أنماط الأقسام الأربعة

استُخدمت نفس دلالات تخطيطات FlixQuest، مع تعيين النوع/التصنيف صراحةً:

| القسم | نوع TMDB | النوع | التخطيط | التكوين المرئي |
|---|---:|---|---|---|
| Action | 28 | Movies | `STILLS` | صف واحد من بطاقات Backdrop عريضة 16:9 |
| Reality | 10764 | Series | `STILLS_AND_POSTERS` | صف Backdrop عريض ثم صف ملصقات Poster |
| Mystery | 9648 | Series | `TWO_POSTER_ROWS` | صفان منفصلان من ملصقات Poster |
| History | 36 | Movies | `FEATURE` | بطاقة Featured عريضة أولًا ثم صف ملصقات Poster |

تُعرض تسمية النوع الصغيرة فوق الاسم (`MOVIES` أو `SERIES`) مثل FlixQuest. وتُنفّذ الأزرار وSee all عبر وجهات CineStream الحالية، لا عبر Flutter routes.

## 5. ملفات CineStream المسؤولة عن التنفيذ

- `app/src/main/java/com/example/ui/screens/home/HomeScreen.kt`
  - ترتيب أقسام LazyColumn، الربط بين الرفوف ومصادرها، تصفية الأنواع، وأفعال النقر والضغط المطوّل.
- `app/src/main/java/com/example/ui/screens/home/HomeViewModel.kt`
  - تحميل التغذيات، فصل اليومي عن الأسبوعي، اختيار الأنواع الأربعة الثابتة ومعرّفاتها، والتدفقات المؤجلة للـ genres/providers.
- `app/src/main/java/com/example/ui/screens/home/FlixQuestHomeComponents.kt`
  - البطاقات العريضة، البطاقة المميزة، رفوف Posters وStills، خدمات البث ورفوف مزودي المشاهدة.
- `app/src/main/java/com/example/ui/components/HeroCarousel.kt`
  - البانر الدوار، وحتى 10 شرائح، تبديل تلقائي كل 7 ثوانٍ في وضع Home، إيقاف التبديل أثناء تمرير الصفحة أو السحب داخل البانر، ومؤشرات الشرائح.
- `app/src/main/java/com/example/data/remote/TmdbApiService.kt`
- `app/src/main/java/com/example/data/repository/TmdbMediaRepositoryImpl.kt`
- موارد النصوص في `app/src/main/res/values/strings.xml` و`app/src/main/res/values-ar/strings.xml`.

## 6. الحفاظ على وظائف المشروع

- لم تُحذف مكتبات أو ملفات من CineStream.
- لم تُنقل اعتمادات Flutter أو Dart إلى مشروع Android Compose.
- لا يتغير مشغل الفيديو أو نظام الإضافات أو التنزيلات أو Firebase.
- النقر على البطاقات يظل متصلًا بمسارات تفاصيل CineStream، والضغط المطوّل يبقي قائمة إجراءات البطاقة.
- مكتبة المستخدم تُقرأ من `LibraryRepository` المحلية ولا تُستبدل بكتالوج ثابت.
- Continue Watching يستعمل سجل المشاهدة وعمليات التشغيل القائمة.
- كل Genre/Provider feed يحمّل عند الحاجة، وتُشارك StateFlow لتخفيف الطلبات المتكررة.

## 7. نتائج التحقق والحدود

تم إعداد سجل تحقق مستقل يتحقق من:

- ترتيب مفاتيح أقسام Home في `HomeScreen.kt`.
- ترتيب Netflix ثم Disney+ ثم Prime Video.
- وجود الأنواع الأربعة فقط في `HomeViewModel.kt` مع IDs وأنواعها وتخطيطها المحدد.
- تطابق تخطيط Compose مع دلالة كل تخطيط في FlixQuest.
- الفصل بين مصادر TMDB اليومية والأسبوعية والشائعة والأعلى تقييمًا والقادمة.
- سلامة XML وموارد الترجمة العربية والإنجليزية وعدم تكرار أسماء النصوص.
- وجود موارد الشعارات المشار إليها في كود Home.
- سلامة أرشيف ZIP.

**بناء APK:** لم يتم إثبات `assembleDebug` في هذه البيئة؛ ملف Gradle Wrapper يطلب Gradle 8.9 من `services.gradle.org`، ولا توجد نسخة Gradle 8.9 مخزنة محليًا. لا يُدّعى نجاح تجميع Kotlin الكامل أو اختبار تشغيل على جهاز حقيقي. يجب تشغيل GitHub Actions في بيئة متصلة لاعتماد البناء.

## 8. توضيح مهم

هذه المرحلة تثبّت قائمة Home التي طلبها المستخدم، وتُبقي البيانات والصفحات الأخرى الموجودة في التطبيق. حذف قسم من Home يعني حذف عرضه من قائمة Home فقط، وليس حذف API أو repository أو الشاشة المقابلة من التطبيق؛ وذلك لتجنب كسر بقية الوظائف.
