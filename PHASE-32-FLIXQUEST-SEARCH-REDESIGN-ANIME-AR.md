# PHASE 32 — إعادة بناء صفحة البحث وفق FlixQuest مع دعم الأنمي

**المشروع:** CINESTREAM PRO  
**المرجع:** FlixQuest source (`lib/mobile/screens/search_screen.dart` وما يتصل بها)  
**النطاق:** صفحة Search، سجل البحث، الاقتراحات الرائجة، تصنيفات الأفلام والمسلسلات والأنمي، وربط الانتقال إلى Discover.

## 1. مراجعة FlixQuest قبل التعديل

تمت مراجعة تنفيذ شاشة البحث في FlixQuest، وبالأخص:

- `lib/mobile/screens/search_screen.dart`: بنية شاشة البحث، شريط البحث المثبت، سجل البحث، بطاقة Browse with filters، Top searches، وفلاتر نتائج البحث.
- `lib/catalog/catalog_controller.dart` و`lib/catalog/media_search.dart`: تحميل اقتراحات البحث وبيانات الوسائط.
- `lib/models/genres.dart`: بيانات التصنيفات.
- `lib/mobile/screens/search/discover_screen.dart`: الانتقال من Browse with filters وتصنيفات الأنواع إلى شاشة التصفية.
- عناصر العرض المساعدة مثل `media_art.dart` و`category_section.dart` و`section_header.dart` و`pill_button.dart`.

الصفحة المرجعية ليست مجرد حقل بحث. إنها صفحة استكشاف ذات حالة ابتدائية قبل الكتابة، ثم تتحول إلى واجهة نتائج عند إدخال نص.

## 2. التنفيذ في CINESTREAM PRO

### الحالة الابتدائية

- شريط بحث مثبت أعلى قائمة التمرير.
- سجل بحث محلي حقيقي، لا قائمة تجريبية ثابتة.
- حد أقصى 12 عملية بحث حديثة، مع إمكانية حذف عنصر أو مسح السجل.
- بطاقة Browse with filters تفتح Discover.
- قسم Top searches يعتمد على تغذيات المستودع الفعلية للأفلام والمسلسلات والأنمي، مع دمج العناصر بالتناوب وإظهار النوع والسنة والتقييم.
- أقسام شبكية بعمودين لكل من Movie genres وSeries genres وAnime genres.

### نتائج البحث

- الفلاتر الحالية: All وMovies وSeries وAnime.
- تُحافظ نتائج الأنمي على هويتها المستقلة باستخدام `ContentTypeResolver` و`AnimePlaybackStore` عند فتح النتيجة.
- تُحافظ نتائج الأفلام والمسلسلات على مسارات التفاصيل والتشغيل والحفظ في المكتبة الموجودة.
- أزيلت بيانات البحث التجريبية الثابتة من الصفحة.

### الانتقال من التصنيف إلى Discover

تمت إضافة `DiscoverLaunchSelection` كتمرير لمرة واحدة بين شاشة البحث وDiscover؛ عند اختيار تصنيف فيلم أو مسلسل أو أنمي، تفتح Discover على النوع المحدد وتطبّق genre ID المختار بدل فتح صفحة عامة بلا تحديد.

## 3. إضافة الأنمي

أضيف قسم مستقل `Anime genres` باستخدام `TmdbGenreHelper.getAnimeGenres()`، وأضيف خيار Anime إلى فلاتر النتائج. يتم التعرف على أنمي المسلسلات عبر `ContentTypeResolver.resolveSeries`، ويظل توجيهها إلى شاشة تفاصيل الأنمي/المسلسل مع تسجيلها في `AnimePlaybackStore`.

الأفلام اليابانية المتحركة تظل من الناحية البنيوية أفلامًا داخل نموذج CINESTREAM PRO؛ لم أغيّر نوعها إلى TV Anime، احترامًا لقاعدة `ContentTypeResolver` التي تعتبر وسيط الفيلم هو المرجع الأساسي.

## 4. ما لم يُنقل حرفيًا ولماذا

- **FlixAI CTA:** لم أضف بطاقة FlixAI شكلية؛ لا توجد في نسخة CINESTREAM PRO الحالية شاشة FlixAI أو خدمة توصيات محادثية متكاملة يمكن توصيل البطاقة بها. إضافة زر لا ينفذ وظيفة حقيقية ستكون مضللة.
- **People results:** واجهة البحث الحالية ومستودع `MediaRepository.searchMulti` في CINESTREAM PRO يعيدان أفلامًا ومسلسلات فقط؛ لا يوجد مسار نتائج أشخاص مرتبط بواجهة البحث الحالية. لذلك لا تعرض خانة البحث ادعاء دعم People، ولم أضف فلتر People غير موصول. يحتوي المشروع على شاشة تفاصيل الأشخاص المستخدمة من صفحات أخرى، لكن وصلها بالبحث يحتاج توسيع عقد البحث وبيانات `Person` في المستودع.
- **Top searches:** الاسم والهيكل المرئي مستوحيان من FlixQuest، لكن CINESTREAM PRO لا يحتوي على endpoint لسجل أكثر عبارات البحث استخدامًا. لذلك تُملأ القائمة من تغذيات Trending الحقيقية للأفلام والمسلسلات والأنمي، وليست إحصائية لعبارات بحث المستخدمين.

## 5. ملفات المصدر التي تغيّرت

- `app/src/main/java/com/example/ui/screens/search/SearchScreen.kt`
- `app/src/main/java/com/example/ui/screens/search/SearchViewModel.kt`
- `app/src/main/java/com/example/ui/screens/home/DiscoverScreen.kt`
- `app/src/main/java/com/example/ui/screens/home/DiscoverLaunchSelection.kt` (ملف جديد)
- `app/src/main/java/com/example/navigation/AppNavigation.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-ar/strings.xml`

## 6. التوافق والتنفيذ

- أبقيت المشروع الأصلي على Kotlin وJetpack Compose ومكتباته الحالية.
- لم أضف مكتبة Flutter أو خدمة خارجية أو API جديدًا.
- تم استخدام `LazyColumn` مع `stickyHeader` لقائمة البحث، و`FlowRow` لشبكات التصنيفات، و`AsyncImage` لصور النتائج.
- أصلحت استيراد Pull-to-refresh إلى حزمة Material3 الصحيحة، وأضفت استيراد `Surface`، وأضفت `ExperimentalLayoutApi` إلى الدالة التي تستخدم `FlowRow`.
- تم استبعاد عناصر الأنمي من تغذية المسلسلات العامة قبل دمج Top searches، لتجنب ظهورها مرتين.

## 7. ما يحتاج اختبارًا بعد التسليم

1. فتح Search من شريط التنقل والتأكد من بقاء حقل البحث مثبتًا أثناء التمرير.
2. تنفيذ بحث عادي ثم التبديل بين All وMovies وSeries وAnime.
3. الضغط على كل تصنيف والتأكد من فتح Discover بالنوع ومعرف التصنيف الصحيح.
4. التحقق من أن الأنمي يفتح بمسار الأنمي الصحيح ولا يُعامل كمسلسل عادي.
5. تجربة سجل البحث بعد إغلاق التطبيق وإعادة فتحه.
6. اختبار الصور وسرعة التمرير على جهاز Android حقيقي.

**حالة البناء:** لم يتم تأكيد `assembleDebug`. محاولة Gradle المحلية توقفت قبل تشغيل Kotlin compiler بسبب `UnknownHostException: services.gradle.org` أثناء تنزيل Gradle 8.9. لذلك فحوص XML والموارد ومراجعة الصياغة لا تعادل نجاح بناء Android الكامل.
