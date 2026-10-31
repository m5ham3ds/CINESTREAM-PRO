# إشعارات مصادر الطرف الثالث — تكامل المشغّل والمصادر

تاريخ الإعداد: 2026-10-30

## Zangetsu

- المستودع الذي تمت مراجعته: https://github.com/Spyou/Zangetsu
- الترخيص الموجود في الأرشيف: GNU GPL v3.0.
- الملفات التي تم تحليلها: `providers/allanime.js`، و`providers/netmirror.js`، ومجلد `extractors/`، إضافة إلى بنية تسجيل المستودعات والمصادر في `lib/core/provider/`.
- أُنشئ محوّل Kotlin أصلي لسلوك مصدر AllAnime في `app/src/main/java/com/example/extension/zangetsu/ZangetsuAllAnimeClient.kt`. يدعم البحث بالعنوان واستخراج روابط الفيديو المباشرة وواجهات clock وروابط HLS/DASH مع الترويسات.
- أُضيفت محوّلات Kotlin محدودة النطاق لبعض المستخرجات العامة في `ZangetsuEmbedExtractorClient.kt`: OK.ru/Odnoklassniki وStreamlare وDoodStream وMP4Upload المباشر. MP4Upload لا يدعم بعد الصفحات التي تتطلب فك packed JavaScript.
- لا يتضمن المحوّل تشغيل JavaScript عامًّا ولا يشغّل إضافات CloudStream أو Aniyomi/Mihon أو MangaYomi أو LNReader؛ فهذه تحتاج بيئات تشغيل منفصلة.
- لم يُنقل مسار NetMirror الذي يرسل قيمة CAPTCHA تجريبية إلى `verify.php`، ولم تتم إضافة أي تجاوز تلقائي للتحقق البشري.

## FlixQuest

- الأرشيف الذي تمت مراجعته: `flixquest-main`; يتضمن عقد `openapi.json` للواجهة `FlixQuest Scraper API v2`، وملف `lib/video_providers/vixsrc.dart`.
- التطبيق يستخدم `better_player_plus` من مسار مستودع منفصل، وDio وProvider. لم تُنقل واجهة Flutter نفسها إلى Compose؛ أُعيد تنفيذ سلوك المصدر/السيرفر في Kotlin واستخدم مشغّل Media3 الأصلي الحالي.
- عنوان خادم Scraper API غير موجود كعنوان تشغيل حقيقي في الأرشيف؛ لذلك يجب إدخاله بواسطة إعداد `FLIXQUEST_API_URL` من مالك خدمة مخوّل. لم يوضع عنوان مفترض في الكود.
- نسخة Better Player المعدلة ليست ضمن الأرشيف المرفق؛ لذلك لم يدّعِ هذا التغيير أنه نسخة حرفية كاملة من مكتبة المشغّل.

## ملاحظة ترخيصية

يتضمن التكامل منطقًا وبروتوكولات مستفادة/مكيّفة من مشروعين مرخصين بـ GPL-3.0. احتُفظ بنسختي الترخيص هنا للتوثيق. قبل توزيع نسخة مشتقة، يجب مراجعة توافق ترخيص العمل ككل والالتزامات المتعلقة بإتاحة المصدر والنسب. هذا الإشعار لا يُعد رأيًا قانونيًا ولا يحل محل مراجعة الترخيص.
