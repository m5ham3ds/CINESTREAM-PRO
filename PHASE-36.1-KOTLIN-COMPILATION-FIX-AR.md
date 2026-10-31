# PHASE 36.1 — إصلاح أخطاء تجميع Kotlin بعد PHASE 36

التاريخ: 31 أكتوبر 2026  
المشروع: CINESTREAM-PRO  
النسخة الأساس: `CINESTREAM-PRO-PHASE36-SEASON-PAGES.zip`

## 1. سبب فشل GitHub Actions

فشل البناء في `:app:compileDebugKotlin`، وليس في تنزيل Gradle أو Firebase. سجل البناء كشف ثلاثة أخطاء أصلية:

1. `DetailsScreens.kt:2765` — تعذر إعادة إسناد `type` لأن المتغير المحلي `val type` حجب خاصية `Intent.type` داخل `apply`.
2. `DetailsScreens.kt:2793` — المرجع `LazyColumn` غير معروف بسبب غياب استيراده. بقية الأخطاء التي تتحدث عن `items`, و`seasonNumber`, و`episodeCount`، وسياق Composable كانت نتائج متسلسلة لهذا الخطأ.
3. `SearchScreen.kt:27` — استيراد `androidx.compose.foundation.lazy.stickyHeader` غير صحيح؛ `stickyHeader` جزء من `LazyListScope` ويُستدعى مباشرة داخل DSL الخاص بـ`LazyColumn`.

## 2. الإصلاحات

### `DetailsScreens.kt`

- إضافة `import androidx.compose.foundation.lazy.LazyColumn` بجانب استيرادات القوائم الكسولة الحالية.
- إعادة تسمية المتغير المحلي إلى `mediaRoute` لتجنب حجب خاصية Intent.
- تعيين نوع مشاركة Android صراحة بواسطة `this.type = "text/plain"`.
- لم يتغير رابط TMDB أو عنوان المشاركة أو Extras أو سلوك المشاركة.

### `SearchScreen.kt`

- حذف الاستيراد غير الصحيح `androidx.compose.foundation.lazy.stickyHeader`.
- الإبقاء على استدعاء `stickyHeader(key = "search_input_header")` داخل نطاق `LazyColumn`؛ لم يتم إلغاء تثبيت رأس البحث أو تحويله إلى عنصر عادي.
- بقي `ExperimentalFoundationApi` مفعّلًا كما في الملف.

## 3. ما لم يتغير

- لا تغييرات على واجهة الموسم أو اختيار الموسم أو بيانات الحلقات.
- لا تغييرات على المشغل أو التنزيل أو نظام الإضافات أو Firebase أو Cloudflare Worker.
- لا مكتبات جديدة، ولا تغيير لإصدارات Gradle أو Kotlin أو Compose أو AGP.
- تحذير توافق AGP 8.7.2 مع `compileSdk 36` وتحذير native strip ليسا سبب الفشل الظاهر في سجل البناء.

## 4. مخرجات التحقق

راجع `PHASE-36.1-VALIDATION-LOG.txt` للاختبارات المنفذة وحدودها.

**لم يتم تأكيد بناء APK:** بيئة العمل لم تستطع تنزيل Gradle 8.9 بسبب `UnknownHostException: services.gradle.org`. لذلك الحزمة تحتوي إصلاحات أخطاء السجل المرفق، لكن يجب تشغيل GitHub Actions مرة أخرى لإثبات التجميع الكامل.
