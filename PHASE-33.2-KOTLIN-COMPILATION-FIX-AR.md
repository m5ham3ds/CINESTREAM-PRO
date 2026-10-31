# PHASE 33.2 — إصلاح أخطاء تجميع Kotlin

**المشروع:** CINESTREAM PRO  
**المرحلة السابقة:** PHASE 33.1 — إصلاح موارد Unicode  
**الهدف:** معالجة أخطاء `compileDebugKotlin` التي ظهرت في سجل GitHub Actions المرفق.

## ملخص الأخطاء الواردة في سجل البناء

1. `DiscoverScreen.kt:455` — تمرير `IntProgression` إلى دالة `items` التي تتطلب عددًا صحيحًا أو قائمة عناصر.
2. `DiscoverScreen.kt:456` — استدعاء Compose داخل lambda لم تُحلّ إلى محتوى عنصر قابل للتركيب بسبب اختيار overload غير مناسب لـ `items`.
3. `NewAndHotScreen.kt:201-205` — استدعاء `stringResource` من داخل نطاق `LazyListScope` بدل نطاق `@Composable`.
4. `SearchScreen.kt:27` — `stickyHeader` ليس top-level import مستقلًا في إصدار Compose Foundation المستخدم؛ إنه عضو في `LazyListScope` ويُستدعى داخل DSL الخاص بـ `LazyColumn`.

## الإصلاحات المنفذة

### 1) DiscoverScreen.kt

- حُوّلت سنوات البحث من `IntProgression` مباشرةً إلى `List<Int>` ثابتة باستخدام `remember`.
- أصبح `LazyRow` يستعمل `items(items = yearOptions, key = { year -> year })`.
- بقي ترتيب السنوات تنازليًا من السنة الحالية إلى 1950، وبقي سلوك اختيار السنة وإلغاء اختيارها كما هو.

### 2) NewAndHotScreen.kt

- نُقلت قائمة عناوين الأقسام الخمسة خارج lambda الخاصة بـ `LazyRow`.
- تُحل قيم `stringResource` الآن داخل جسم الدالة composable، ثم تُمرر النصوص المحسوبة إلى عناصر القائمة.
- لم تتغير الأقسام أو مفاتيحها أو طريقة تحديد القسم الحالي.

### 3) SearchScreen.kt

- أزيل الاستيراد غير الصحيح `androidx.compose.foundation.lazy.stickyHeader`.
- بقي استدعاء `stickyHeader` داخل `LazyColumn`، حيث يتوفر كعضو في `LazyListScope`.
- بقي `ExperimentalFoundationApi` ضمن `@OptIn` في الدالة.

## الملفات المتغيرة

- `app/src/main/java/com/example/ui/screens/home/DiscoverScreen.kt`
- `app/src/main/java/com/example/ui/screens/home/NewAndHotScreen.kt`
- `app/src/main/java/com/example/ui/screens/search/SearchScreen.kt`

## ما لم يتغير

- موارد Unicode التي عولجت في PHASE 33.1.
- منطق TMDB، فلاتر الأفلام والمسلسلات والأنمي، تدفقات الترند، صفحة التفاصيل، البحث، المشغّل، التنقل، إعدادات Gradle، ونسخ المكتبات.

## حدود التحقق

تم التحقق من مواضع الإصلاح، وبنية XML، ومراجع الاستيراد المتأثرة، وسلامة أرشيف ZIP. جرت محاولة تشغيل `./gradlew --no-daemon :app:compileDebugKotlin` محليًا، لكنها توقفت قبل تشغيل Gradle بسبب `UnknownHostException: services.gradle.org` عند تنزيل Gradle 8.9. لذلك لا يُدّعى أن التجميع الكامل نجح؛ يجب تأكيده في GitHub Actions.
