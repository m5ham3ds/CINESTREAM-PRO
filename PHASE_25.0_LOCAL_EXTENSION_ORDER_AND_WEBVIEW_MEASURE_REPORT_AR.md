# Phase 25.0 — توحيد مصدر ترتيب الإضافات وقياس صفحة WebView

## ملخص
تم تعديل نسخة المشروع المشتقة من Phase 24.9 في مسارين مستقلين. لم تُجرَ تعديلات على منطق نجاح/فشل Cloudflare أو حفظ الجلسة؛ التغيير في WebView يقتصر على قياس هندسة المستند والإطار ثم التمرير المحسوب داخل WebView الموجود في مساحة المشغّل.

## 1) مصدر واحد لترتيب البحث وحالة الإضافة
- `DefaultSearchOrderRepository.getSearchOrder()` أصبح يقرأ من `ManagedExtensionRuntimeRegistry` المحلي، ولا يستدعي `FirebaseSearchOrderDataSource.fetchSearchOrder()` أثناء البحث أو التشغيل.
- مزامنة Firestore الحية تظل في `ManagedExtensionRealtimeSyncManager` وتحدّث الـ Registry؛ لذلك تستفيد الصفحة ومحركات البحث من اللقطة نفسها.
- `SearchManagedExtensionsUseCase` لم يعد يختار قائمة احتياط مرتبة حسب أولوية محلية عندما لا توجد قائمة ترتيب؛ المرشحون الآن هم فقط معرّفات الترتيب المحلي المتاحة والمؤهلة.
- `PlaybackOrchestrator` لم يعد يتجاوز ترتيب الأدمن بفرز احتياطي حسب `priority` عندما تكون قائمة الترتيب فارغة أو غير متاحة.
- مسار `DefaultControlledManagedExtensionRuntime` في Phase 24.9 يواصل إعادة مطابقة المرشحين مع لقطة Registry المحلية واستبعاد ما ليس ACTIVE أو ما هو disabled، دون إجراء قراءة شبكة.

### السلوك المقصود
1. تستقبل طبقة المزامنة التغييرات وتحدّث الـ Registry.
2. صفحة Extensions تعرض اللقطة الحالية نفسها.
3. البحث والتشغيل يقرآن الترتيب من اللقطة المحلية نفسها.
4. تتم تصفية الإضافات المعطلة دون إعادة ترتيب المتبقي منها.
5. إذا لم توجد قائمة ترتيب محلية صالحة، لا يتجاوز التطبيق ذلك باستخدام ترتيب الأولوية المحلي.

## 2) قياس WebView بدل قيمة تمرير ثابتة
- أزيل التمرير الثابت 420 CSS px.
- أول محاولة تقيس `scrollHeight` و`innerHeight` وموضع التمرير الحالي.
- تحاول تحديد إطار Cloudflare/Turnstile وقياسه عبر `getBoundingClientRect()`.
- تحسب مقدار التمرير المطلوب بناءً على موضع الإطار بالنسبة إلى نافذة المستند، ثم تعيد القياس بعد استقرار التخطيط.
- لا يتم النقر على مربع التحقق تلقائيًا، ولا تتغير مساحة عرض صفحة التحقق خارج المشغل.
- لا يمكن للصفحة الأم قياس المحتوى الداخلي لإطار cross-origin نفسه؛ القياس يحدد موضع الإطار الخارجي ويستخدم طول المستند كاحتياط إذا لم يُدرج الإطار بعد.

## 3) المساحة العلوية
- تم الاحتفاظ بإزالة insets الزائدة في `ExtensionsScreen` من Phase 24.9 عبر تعيين `contentWindowInsets` إلى صفر في Scaffold الداخلي.

## الفحوص
اجتازت 9 فحوص ساكنة:
- لا يوجد استدعاء مباشر لـ `fetchSearchOrder()` داخل مستودع القراءة الذي تستعمله عمليات البحث/التشغيل.
- البحث يعتمد فقط على ترتيب Registry المحلي.
- التشغيل لا يحتوي على fallback لفرز الإضافات حسب priority.
- الترتيب المحفوظ هو ترتيب المرشحين الممرر إلى Eligibility Filter.
- كود WebView يقيس طول المستند وارتفاع النافذة.
- كود WebView يقيس هندسة إطار التحقق.
- لا توجد قيمة تمرير ثابتة 420.
- بقي تحدي Cloudflare ضمن `TurnstilePositioningEngine` داخل WebView.
- بقيت إزالة المساحة العلوية في Scaffold الداخلي.

## ما لم يتم اختباره
- لم يتم تشغيل Gradle أو بناء APK.
- لم يتم تشغيل التطبيق على جهاز فعلي.
- لم يتم الاتصال بقاعدة Firebase الحية.
- يجب اختبار تحديث تعطيل الإضافة وترتيبها من الأدمن، ثم الضغط على Play والتأكد من أن المصدر الأول المستخدم يطابق قائمة Extensions الحالية.
- يجب اختبار التمرير على صفحة Cloudflare فعلية؛ قياسات الصفحة الأم لا تكشف المحتوى الداخلي cross-origin، وقد تحتاج بعض الصفحات إلى سلوك خاص بها.

## ملفات المصدر الرئيسية
- `app/src/main/java/com/example/extension/managed/searchorder/SearchOrderRepository.kt`
- `app/src/main/java/com/example/extension/managed/usecase/SearchManagedExtensionsUseCase.kt`
- `app/src/main/java/com/example/extension/managed/playback/PlaybackOrchestrator.kt`
- `app/src/main/java/com/example/ui/components/InteractiveChallengeWebView.kt`
- `app/src/main/java/com/example/ui/screens/extensions/ExtensionsScreen.kt`
