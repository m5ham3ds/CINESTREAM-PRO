# PHASE 31 — إعادة بناء Discover وNew & Hot وفق FlixQuest مع دعم الأنمي

**المشروع:** CINESTREAM PRO  
**المرحلة:** 31  
**التاريخ:** 2026-10-30  
**الحالة:** تنفيذ المصدر والواجهات مكتمل؛ بناء APK والاختبار الحي غير مؤكدين بسبب تعذر تنزيل Gradle في بيئة الفحص.

---

## 1. نطاق التدقيق قبل التعديل

تمت مراجعة ملفات FlixQuest التالية مباشرةً من الأرشيف المرفق:

- `lib/mobile/screens/discover_screen.dart` — واجهة الفلاتر، أنواع المحتوى، ترتيب الفلاتر، مزودو البث، السنوات، التصويتات، وحالات المسلسلات.
- `lib/catalog/new_and_hot.dart` — تعريف شرائح New & Hot ومصادر بيانات Coming Soon وEveryone Watching وTop 10.
- `lib/mobile/screens/new_and_hot_screen.dart` — البطاقات الرئيسية، عرض Coming Soon، قائمة Everyone Watching، والقوائم المرتبة Top 10.
- المكونات المشتركة التي تستخدم `MediaArt` وعرض العناوين/الصور.

كما تمت مراجعة الملفات الأصلية المقابلة في CINESTREAM PRO:

- `ui/screens/home/DiscoverScreen.kt`
- `ui/screens/home/NewAndHotScreen.kt`
- `ui/screens/home/HomeViewModel.kt`
- `ui/screens/home/FlixQuestHomeComponents.kt`
- `data/remote/TmdbApiService.kt`
- `data/repository/TmdbMediaRepositoryImpl.kt`
- `domain/repository/MediaRepository.kt`
- `navigation/AppNavigation.kt`
- موارد النصوص العربية والإنجليزية.

لم يتم نسخ واجهات Flutter حرفيًا إلى تطبيق Kotlin؛ تمت إعادة تنفيذ السلوك باستخدام Jetpack Compose وطبقة البيانات الحالية في CINESTREAM PRO، لأن التطبيق الأصلي لا يشغّل Flutter runtime.

## 2. New & Hot — التنفيذ الجديد

أعيد بناء الصفحة لتتبع تقسيم FlixQuest، مع إضافة قسم مستقل للأنمي:

1. **Coming Soon** — يجمع الأفلام والمسلسلات والأنمي القادم، ثم يصنف العناصر حسب تاريخ الإصدار/العرض. تُستبعد التواريخ غير الصالحة والقديمة، وتبقى هوية الأنمي منفصلة في نوع المحتوى.
2. **Everyone’s Watching** — يستخدم الترند العام بدل سجل مشاهدة المستخدم الشخصي، ويمزج الأفلام والمسلسلات والأنمي بالتناوب، مع إزالة التكرار.
3. **Top 10 Movies** — يعرض حتى عشرة عناصر من ترند الأفلام، مع الرجوع إلى قائمة الأعلى تقييمًا عند فراغ الترند.
4. **Top 10 Series** — يعرض المسلسلات فقط ويستبعد عناصر الأنمي المصنفة ضمن TV.
5. **Top 10 Anime** — قسم جديد مخصص للأنمي، يعتمد ترند الأنمي ثم قائمة الأعلى تقييمًا كاحتياط.

تم نقل نمط البطاقات الظاهر في المراجع: صورة خلفية عريضة بنسبة 16:9، العنوان، نوع العمل والسنة والتقييم، الوصف، وأزرار Play وMy List حيث يلزم. تستخدم My List مستودع المكتبة الحالي، ويُسجل الأنمي بنوع `ContentType.ANIME` لا كمسلسل عادي.

تظل بيانات New & Hot مستمدة من تغذيات TMDB الحالية في التطبيق. لا تدّعي هذه المرحلة أنها نقلت خدمة Scraper API مغلقة المصدر من FlixQuest.

## 3. Discover — التنفيذ الجديد

أعيد بناء صفحة Discover وفق ترتيب FlixQuest:

- محدد نوع المحتوى: Movies / Series / Anime.
- بطاقات فرز: Most popular، Least popular، Highest rated، Lowest rated.
- الأنواع الفنية في صفوف قابلة للالتفاف.
- شبكة مزودي البث بصور العلامات الموجودة في موارد CINESTREAM PRO.
- اختيار سنة محددة أو Any.
- الحد الأدنى لعدد التصويتات: Any، 100+، 1K+، 5K+، 10K+، 20K+.
- فلتر حالة المسلسل، وحالة الأنمي بصورة مستقلة.
- زر ثابت أسفل الصفحة يعرض عدد النتائج المتطابقة بعد جلب الصفحة الأولى.
- شاشة نتائج مستقلة مع تحميل صفحات إضافية وإزالة التكرار.

تم الاحتفاظ بحالة فلاتر مستقلة لكل نوع محتوى عند التبديل بين Movies وSeries وAnime.

### 3.1 نقل الفلاتر إلى الخادم

أضيف نموذج `MediaDiscoverRequest`، وأضيفت دالتا `discoverMovies` و`discoverSeries` إلى `MediaRepository`، ثم نُفذتا في `TmdbMediaRepositoryImpl`.

| الفلتر | معامل TMDB |
|---|---|
| الفرز | `sort_by` |
| الأنواع الفنية | `with_genres` |
| السنة | `primary_release_date.gte/lte` للأفلام، و`first_air_date.gte/lte` للمسلسلات |
| الحد الأدنى للتصويتات | `vote_count.gte` |
| منصات المشاهدة | `with_watch_providers` مع `|` لربط الخيارات بمنطق OR |
| منطقة توفر المنصات | `watch_region=US` مثل FlixQuest |
| حالة المسلسل/الأنمي | `with_status` |

تُرسل فلاتر النتائج إلى مسارات Discover عبر عميل TMDB الحالي في CINESTREAM PRO، ولا تُطبّق على أول مجموعة محلية صغيرة من العناصر.

### 3.2 فصل الأنمي

لمنع اختلاط الأنمي مع المسلسلات العادية:

- يضيف طلب Anime التصنيف `16` (Animation) إلى `with_genres`.
- يرسل `with_original_language=ja`.
- يمرر اختيار الأنواع الفنية الإضافية المختارة من الواجهة.
- يجري تحققًا إضافيًا من `genre_ids` واللغة الأصلية قبل تحويل النتائج إلى `ContentType.ANIME`.
- تُستبعد عناصر الأنمي من نتائج Series العادية.

الأنمي ليس مجرد تسمية على الواجهة؛ له نوع محتوى وفلاتر ونتائج منفصلة.

## 4. التنقل إلى التفاصيل

تُفتح الأفلام عبر مسار تفاصيل الأفلام، وتُفتح المسلسلات والأنمي عبر مسار تفاصيل TV الحالي الذي يستخدمه تطبيق CINESTREAM PRO بالفعل في تدفق Anime. يمرر زر Play إلى مسارات تفاصيل التشغيل التلقائي الموجودة أصلًا. لم تتم إضافة Player جديد أو محرك تشغيل موازٍ.

## 5. الملفات التي أضيفت أو عُدلت

### ملفات جديدة

- `app/src/main/java/com/example/domain/models/MediaDiscoverRequest.kt`
- `PHASE-31-FLIXQUEST-DISCOVER-NEW-HOT-ANIME-AR.md`
- `PHASE-31-VALIDATION-LOG.txt`

### ملفات معدلة

- `app/src/main/java/com/example/domain/repository/MediaRepository.kt`
- `app/src/main/java/com/example/data/remote/TmdbApiService.kt`
- `app/src/main/java/com/example/data/repository/TmdbMediaRepositoryImpl.kt`
- `app/src/main/java/com/example/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/example/ui/screens/home/DiscoverScreen.kt`
- `app/src/main/java/com/example/ui/screens/home/NewAndHotScreen.kt`
- `app/src/main/java/com/example/navigation/AppNavigation.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ar/strings.xml`

## 6. المكتبات والمعمارية

لم تتم إضافة حزم Flutter أو Better Player أو مكتبات حركة جديدة. التنفيذ يستخدم المكتبات الموجودة في CINESTREAM PRO:

- Jetpack Compose / Material 3 لواجهات الصفحتين.
- Navigation Compose للتنقل الحالي.
- Retrofit لطلبات TMDB.
- Kotlin Coroutines للتنفيذ غير المتزامن والانتظار 450ms قبل تحديث عدد النتائج.
- Coil لصور الملصقات والخلفيات.
- `LibraryRepository` و`LibraryItem` لخاصية My List.

لا يكرر التنفيذ خدمة صور أو مستودعًا أو محرك تشغيل منفصلًا.

## 7. التحقق المنفذ

- تمت قراءة وتحليل ملفات XML الـ 13 في موارد التطبيق دون أخطاء تحليل.
- ملفات `values/strings.xml` و`values-ar/strings.xml` تحتوي كل منها على 1302 مدخلًا، ولم تظهر أسماء موارد مكررة.
- جرى استخراج كل مراجع `R.string` من الصفحتين والتحقق من وجودها في موارد اللغتين؛ لا توجد مراجع ناقصة.
- فُحصت تعريفات مزودي البث الـ 11 المستخدمة في شبكة Discover.
- لم ينتج عن مسح Kotlin النصي أي تشخيص من نوع `expecting` أو `Unexpected tokens`، لكن تشغيل `kotlinc` منفردًا لم يكن قادرًا على حل Android/Compose/Retrofit لغياب classpath Gradle، ولذلك لا يعد هذا اختبار تجميع.
- محاولة `./gradlew --no-daemon :app:compileDebugKotlin` توقفت قبل بدء بناء المشروع بسبب:

  `java.net.UnknownHostException: services.gradle.org`

لذلك **لم يثبت نجاح assembleDebug ولم يتم اختبار الصفحتين حيًا على جهاز Android**.

## 8. حدود يجب التحقق منها بعد البناء

1. تعتمد Discover على Cloudflare TMDB Gateway المضبوط في `RetrofitClient`. يجب التحقق في CI/الجهاز من أن الخدمة البعيدة تمرر معاملات الاستعلام الجديدة كما هي إلى TMDB؛ مصدر الـ Worker البعيد الذي يشغّل هذا النطاق غير موجود ضمن الأرشيف محل التعديل.
2. بيانات New & Hot تأتي من تغذيات التطبيق الحالية المحدودة بالكمية التي يحملها `HomeViewModel` في هذه المرحلة؛ Top 10 يعرض أول عشرة من هذه التغذيات، وليس مصدر ترتيب عالميًا مستقلاً جديدًا.
3. توفر نتائج الأنمي وخدمات البث يتأثر ببيانات TMDB نفسها وبمنطقة `US` المختارة لمطابقة FlixQuest.
4. يلزم اختبار تغيير الفلاتر بسرعة، ونتائج صفر عنصر، وتحميل الصفحة التالية، ثم اختبار فتح تفاصيل فيلم ومسلسل وأنمي وMy List.

## 9. مرجع معاملات TMDB

- Movie Discover: https://developer.themoviedb.org/reference/discover-movie
- TV Discover: https://developer.themoviedb.org/reference/discover-tv

تؤكد وثائق TMDB دعم `with_watch_providers` مع `watch_region`، وقيم `with_status` من 0 إلى 5، و`vote_count.gte`، وحقول تواريخ الإصدار/العرض.
