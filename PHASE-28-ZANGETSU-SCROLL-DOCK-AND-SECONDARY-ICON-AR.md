# PHASE 28 — مزامنة أيقونة القائمة الثانوية وانكماش شريط التنقل أثناء التمرير

**المشروع:** CINESTREAM PRO  
**المرجع السلوكي:** Zangetsu (`Zangetsu-main.zip`)  
**الحالة:** تنفيذ مصدر + اختبارات سياسة الانكماش؛ لم يُثبت بناء APK في هذه البيئة.

## 1. نتيجة فحص Zangetsu

تمت مراجعة الملفات التالية مباشرة من الأرشيف:

- `lib/features/shell/root_shell.dart`
  - يضع `NotificationListener<ScrollNotification>` حول محتوى الصفحات في `Scaffold.body`، ولذلك لا تحتاج كل صفحة إلى معرفة أن شريط التنقل موجود.
  - يمرّر حالة `dockCollapsedByScroll` إلى الـ dock، ويبقي عناصره قابلة للنقر؛ الانكماش ليس إخفاءً للشريط.
  - ` _FloatingDock` يستخدم انتقالًا مدته 340ms ومنحنى `Cubic(0.32, 0.72, 0, 1)`، ويطوي النصوص ويقلّص مساحة الكبسولة والزر الأوسط.
  - ` _LiquidGlassTabBar` يوفّر مسارًا آخر معتمدًا على `liquid_glass_widgets`، مع تقليص عرض الشريط وارتفاعه ومسافات الأيقونة/النص.
- `lib/core/ui/dock_visibility.dart`
  - العتبة 60 logical px.
  - تجاهل إشعارات المحور الأفقي.
  - تجميع الحركة باتجاه واحد وإعادة العداد عند تغيير الاتجاه لتجنب اهتزاز الشريط بسبب حركات الإصبع القصيرة.
  - إعادة حالة الشريط إلى الوضع الكامل عند العودة إلى أعلى القائمة، باستخدام حالة حافة التمرير بدل انتظار عتبة جديدة.
- `lib/core/ui/nav_prefs.dart`
  - يوجد خيار `keepDockExpanded` لتعطيل الانكماش التلقائي، لكن CINESTREAM PRO لا يملك تفضيلًا مماثلًا حاليًا؛ لذلك طُبّق السلوك المطلوب دون إضافة إعداد جديد.
- `lib/features/shell/root_shell.dart` (`_DockPop` و`_DockItem`)
  - الأيقونة النشطة لديها حركة scale، والنص يُطوى من التخطيط نفسه بدل إخفائه بصريًا فقط.

### المكتبات والحركة في Zangetsu

- `liquid_glass_widgets: ^1.15.0` لتصميم الشريط الزجاجي في مسار liquid.
- `TweenAnimationBuilder` و`AnimationController` و`CurvedAnimation` و`NotificationListener` من Flutter SDK للحركة والمراقبة.
- لا توجد مكتبة مستقلة مخصصة لاكتشاف اتجاه التمرير أو threshold في `DockScrollCollapse`؛ المنطق مكتوب داخل المشروع.

## 2. قرار التكييف مع CINESTREAM PRO

CINESTREAM PRO تطبيق Kotlin + Jetpack Compose وليس Flutter. لذلك لا يمكن ربط حزمة Dart `liquid_glass_widgets` مباشرة بمكوّن Compose دون إدخال Flutter Engine وطبقة Flutter كاملة، وهو خارج نطاق تعديل التنقل الحالي.

تم استخدام المقابلات الأصلية المتوفرة في المشروع دون إضافة اعتماد جديد:

- `NestedScrollConnection` و`Modifier.nestedScroll` لمراقبة تمرير المحتوى، بما في ذلك الحركة المتبقية عند حافة أعلى القائمة.
- `animateFloatAsState` و`CubicBezierEasing` و`AnimatedContent` للحركة، بما فيها pop للأيقونة المحددة.
- `Surface` و`Row` و`Column` و`clipToBounds` لتقليص الكبسولة وطَيّ التسميات.
- إصدار Compose BOM الحالي في المشروع: `2024.10.01`، وKotlin `2.0.21`.

مرجع Android الرسمي:  
https://developer.android.com/reference/kotlin/androidx/compose/ui/input/nestedscroll/nestedScroll.modifier  
يوضح المرجع أن `nestedScroll` على العنصر الأب يستقبل أحداث التمرير من عناصر مثل `LazyColumn` و`verticalScroll` التابعة له، ويمكنه مراقبتها دون استهلاك الحركة.

## 3. التعديلات المنفذة

### 3.1 أيقونة زر القائمة الثانوية

الملف: `app/src/main/java/com/example/ui/components/BottomNavBar.kt`

- عند وجود المسار الحالي ضمن القائمة الثانوية، يُختار رمز الصفحة المقابلة من نفس تعريف القائمة.
- الرمز الأوسط يتغير بحركة `AnimatedContent` (fade + scale) ليصبح رمز Movies أو Series أو Search أو Anime أو Settings بحسب الصفحة الحالية.
- لم تتغير وظيفة الزر: النقر عليه ما زال يبدّل `secondaryOpen` ويفتح القائمة الثانوية أو يغلقها.
- عندما لا يكون المستخدم في وجهة ثانوية، يبقى رمز Apps، وتظهر حركة Add عند فتح القائمة كما كان السلوك السابق.
- عند فتح القائمة من إحدى صفحاتها، تبقى أيقونة الصفحة الحالية في المنتصف، بينما تظل القائمة الثانوية تعرض العنصر المحدد.
- بقي ترتيب القائمة الثانوية كما هو: Movies → Series → Search → Anime → Settings.
- تسمية الزر بقيت Menu / Close menu حتى لا توحي بأن الزر أصبح انتقالًا مباشرًا فقط؛ الرمز يدل على الصفحة الحالية والزر يظل زر القائمة.

### 3.2 الانكماش مع التمرير

الملف: `app/src/main/java/com/example/navigation/AppNavigation.kt`

- أُضيف `NestedScrollConnection` واحد على الـ `Box` الأب الذي يحتوي `NavHost` وشريط التنقل.
- لا تحتاج `HomeScreen` أو Movies أو Series أو Anime أو Discover أو Search إلى إضافة callbacks منفصلة.
- عتبة الحركة 60dp بعد تحويلها إلى pixels عبر `LocalDensity`.
- سحب الصفحة للأعلى (تقدم المحتوى) يؤدي إلى الانكماش؛ والسحب العكسي يؤدي إلى العودة للحالة الكاملة.
- تغيير اتجاه الحركة يعيد العداد إلى الصفر.
- الحركة الأفقية وحدها لا تغيّر حالة الشريط لأن delta العمودي يساوي صفرًا.
- يعاد الشريط للحجم الكامل عند تغيير المسار، حتى لا تنتقل حالة الانكماش من تبويب إلى آخر.
- يعيد `NestedScrollConnection` دائمًا `Offset.Zero`؛ أي إنه لا يستهلك إيماءة المستخدم ولا يغيّر سرعة/مسافة تمرير الصفحة.
- عند وصول المستخدم إلى أعلى القائمة، تكون الحركة باتجاه الأعلى غير مستهلكة من القائمة (`available.y` موجب)؛ عندها يعود الشريط إلى الوضع الكامل فورًا، على نحو مماثل لفحص `metrics.pixels <= 8` في Zangetsu. أما الحركة المتبقية السالبة عند نهاية القائمة فلا تُفسّر خطأً على أنها عودة إلى الأعلى.

الملف الجديد: `app/src/main/java/com/example/ui/components/DockScrollCollapsePolicy.kt`

- يحتوي منطق العتبة واتجاه الحركة في وحدة مستقلة قابلة للاختبار.
- يتعامل مع إشارة Compose السالبة للتمرير إلى الأمام؛ وهي عكس إشارة `ScrollNotification.scrollDelta` في Flutter.

### 3.3 مقاييس الانتقال

| الخاصية | الوضع الكامل | الوضع المنكمش |
|---|---:|---:|
| أقصى عرض للكبسولة | 480dp | 256dp |
| ارتفاع الشريط الرئيسي | 66dp | 54dp |
| أيقونات الوجهات الرئيسية | 23dp | 19dp |
| الزر الأوسط | 42dp | 34dp |
| أيقونة الزر الأوسط | 24dp | 20dp |
| ارتفاع سطر التسمية | 13dp | 0dp |
| مدة الحركة | — | 340ms |
| منحنى الحركة | — | CubicBezier(0.32, 0.72, 0, 1) |

المقاسات تتدرج عبر progress متحرك، وليست قفزة بين حالتين. تبقى مساحات النقر والعناصر فعالة، ويظل الشريط مرئيًا؛ لا يُخفى بالكامل.

### 3.4 إعادة الضبط والتنقل

- تغيير `currentRoute` يغلق القائمة الثانوية كما كان من قبل، ويعيد شريط التنقل إلى الحالة الكاملة.
- لم يتغير `NavController.navigate` أو إعداد `popUpTo` أو `saveState` أو `restoreState`.
- لم يتغير ترتيب عناصر الشريط الرئيسي ولا القائمة الثانوية.
- لم تتغير الشفافية أو ألوان الخلفية أو حدود الشريط.

## 4. الاختبارات المضافة

الملف: `app/src/test/java/com/example/ui/components/DockScrollCollapsePolicyTest.kt`

يحتوي ست حالات:

1. لا ينكمش الشريط قبل تجاوز عتبة الحركة 60.
2. عكس اتجاه التمرير يعيد الشريط بعد تجاوز العتبة العكسية.
3. تغيير اتجاه الحركة يعيد بدء التجميع بدل جمع حركات متعاكسة.
4. التمرير باتجاه أعلى الصفحة يعيد الشريط للحجم الكامل بعد العتبة العكسية.
5. delta عمودي يساوي صفرًا لا يغيّر حالة الشريط، وبذلك لا يتأثر بالسحب الأفقي وحده.
6. الوصول إلى أعلى القائمة يوسّع الشريط فورًا حتى عندما لا تستطيع القائمة استهلاك حركة السحب العكسية.

تم تجميع `DockScrollCollapsePolicy.kt` بمترجم Kotlin المتاح وتشغيل smoke harness مستقل؛ النتيجة: `PASS`. ملف JUnit أُضيف إلى المشروع لكنه لم يُشغّل عبر Gradle في هذه البيئة.

## 5. حدود التحقق

- فُحصت ملفات Zangetsu ذات الصلة بالـ shell والـ scroll collapse والـ dock preferences.
- فُحصت ملفات CINESTREAM PRO التي تم تعديلها ومراجع استخدامها.
- لم تتم إضافة أي مكتبات جديدة أو تحديث إصدارات Compose/Gradle.
- لم يتم تشغيل `assembleDebug` أو اختبار واجهة فعلية على جهاز Android في هذه البيئة؛ يلزم تشغيل GitHub Actions للتحقق من تجميع المشروع والتأكد بصريًا من سرعة الانكماش والمسافات على الجهاز.
- مراقبة `nestedScroll` تغطي عناصر Compose التي تشارك في نظام nested scrolling مثل `LazyColumn` و`verticalScroll`. عناصر AndroidView/WebView التي لا تمرر أحداثها إلى Compose تحتاج interop منفصلًا، ولم يُضف ذلك هنا.

## 6. الملفات المتغيرة

- `app/src/main/java/com/example/ui/components/BottomNavBar.kt`
- `app/src/main/java/com/example/navigation/AppNavigation.kt`
- `app/src/main/java/com/example/ui/components/DockScrollCollapsePolicy.kt` (جديد)
- `app/src/test/java/com/example/ui/components/DockScrollCollapsePolicyTest.kt` (جديد)
- `PHASE-28-ZANGETSU-SCROLL-DOCK-AND-SECONDARY-ICON-AR.md` (هذا التقرير)
- `PHASE-28-VALIDATION-LOG.txt`
