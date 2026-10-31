const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');
function filesUnder(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? filesUnder(full) : [full];
  });
}
const extensionDir = 'app/src/main/java/com/example/extension/managed';
const model = read(`${extensionDir}/model/ManagedExtension.kt`);
const dto = read(`${extensionDir}/repository/ManagedExtensionDto.kt`);
const mapper = read(`${extensionDir}/repository/ManagedExtensionMapper.kt`);
const cache = read(`${extensionDir}/repository/ManagedExtensionCache.kt`);
const sync = read(`${extensionDir}/repository/ManagedExtensionRealtimeSyncManager.kt`);
const context = read(`${extensionDir}/runtime/context/ExtensionContext.kt`);
const playback = read(`${extensionDir}/playback/PlaybackOrchestrator.kt`);
const mediaOrchestrator = read('app/src/main/java/com/example/extension/orchestrator/ManagedMediaOrchestrator.kt');
const searchUseCase = read(`${extensionDir}/usecase/SearchManagedExtensionsUseCase.kt`);
const runtime = read(`${extensionDir}/runtime/DefaultControlledManagedExtensionRuntime.kt`);
const fallback = read(`${extensionDir}/runtime/FallbackManager.kt`);
const rules = read('firestore.rules');

const scrapers = [
  'QfilmScraper.kt',
  'Anime4UpScraper.kt',
  'AnimeBlkomScraper.kt',
  'WitanimeScraper.kt',
  'EgyDeadScraper.kt',
].map(name => [name, read(`${extensionDir}/scraper/${name}`)]);

test('Firebase extension DTO, mapper, realtime sync, and local cache retain Admin-owned fields', () => {
  assert.match(dto, /doc\.getString\("searchUrl"\)/);
  assert.match(dto, /capabilities\s*=\s*capabilities/);
  assert.match(mapper, /searchUrl\s*=\s*dto\.searchUrl/);
  assert.match(mapper, /capabilitiesConfigured\s*=\s*dto\.capabilities\s*!=\s*null/);
  assert.match(cache, /put\("searchUrl",\s*ext\.searchUrl\)/);
  assert.match(cache, /capabilitiesConfigured/);
  assert.match(sync, /localUserEnabled\s*=\s*localEnabled/);
  assert.match(context, /configuredExtension:\s*ManagedExtension\?/);
  assert.match(context, /return configuredExtension \?: ManagedExtension\(/);
});

test('remote searchUrl is consumed by all five bundled scraper search flows', () => {
  for (const [name, source] of scrapers) {
    assert.match(source, /ConfiguredSearchUrl\.build\(/, `${name} must consume Admin searchUrl`);
  }
  const helper = read(`${extensionDir}/scraper/ConfiguredSearchUrl.kt`);
  assert.match(helper, /extension\.searchUrl/);
  assert.match(helper, /validateHttps\(result, "configured search URL"\)/);
  assert.match(helper, /URLEncoder\.encode/);
});

test('Admin capability list is authoritative when explicitly supplied, including an empty list', () => {
  assert.match(model, /val capabilitiesConfigured:\s*Boolean\s*=\s*false/);
  assert.match(model, /if \(capabilitiesConfigured\) capability in capabilities else capability in bundledCapabilities/);
  for (const relative of [
    `${extensionDir}/searchorder/ExtensionEligibilityFilter.kt`,
    `${extensionDir}/runtime/FallbackManager.kt`,
    `${extensionDir}/usecase/ManagedExtensionResolver.kt`,
    `${extensionDir}/runtime/DefaultControlledManagedExtensionRuntime.kt`,
  ]) {
    assert.match(read(relative), /allowsCapability\(/, `${relative} must respect Admin capability configuration`);
  }
});

test('realtime sync preserves local user preference while allowing Admin enabled/status changes', () => {
  assert.match(sync, /val localEnabled = existingById\[dto\.id\.orEmpty\(\)\.trim\(\)\.lowercase\(\)\]\?\.userEnabled \?: true/);
  assert.match(mapper, /enabled\s*=\s*enabled/);
  assert.match(mapper, /status\s*=\s*status/);
});

test('anime-only providers cannot advertise or execute as movies or ordinary series', () => {
  for (const name of ['Anime4UpScraper.kt', 'AnimeBlkomScraper.kt', 'WitanimeScraper.kt']) {
    const source = read(`${extensionDir}/scraper/${name}`);
    assert.match(source, /supportedContentTypes:\s*Set<ContentType>\s*=\s*setOf\(ContentType\.ANIME\)/, name);
    assert.match(source, /val contentType = ContentType\.ANIME|val detectedType = ContentType\.ANIME/, name);
  }
  const runtime = read(`${extensionDir}/runtime/DefaultControlledManagedExtensionRuntime.kt`);
  assert.match(runtime, /val targetContentType = contentType\.takeIf \{ extension\.contentTypes\.contains\(it\) \}/);
});

test('configured Admin search order cannot be bypassed by priority fallback; dubbed mode respects language group', () => {
  assert.match(playback, /if \(searchOrderIds\.isNotEmpty\(\)\) \{[\s\S]*?initialCandidates[\s\S]*?\} else if \(isDubbedSession \|\| adminSearchOrder != null\) \{[\s\S]*?emptyList\(\)/);
  assert.match(playback, /adminSearchOrder\?\.takeIf \{ it\.isDubbedAllowed\(dubbedLanguage\) \}/);
  assert.match(playback, /getDubbedOrder\(dubbedLanguage\)/);
  assert.match(mediaOrchestrator, /getDubbedOrder\(dubbedLanguage\)/);
  assert.match(mediaOrchestrator, /language = dubbedLanguage/);
});

test('regular search queries providers in the canonical Admin search order', () => {
  assert.match(searchUseCase, /private val searchOrderRepository:\s*SearchOrderRepository/);
  assert.match(searchUseCase, /getOrderForContentType\(contentType\)/);
  assert.match(searchUseCase, /configuredOrder\.mapNotNull \{ eligibleById\[it\.trim\(\)\.lowercase\(\)\] \}/);
  assert.match(mediaOrchestrator, /searchOrderRepository = searchOrderRepository/);
});

test('an explicitly configured empty Admin order never falls back to local priority', () => {
  assert.match(searchUseCase, /val adminSearchOrder = searchOrderRepository\.getSearchOrder\(\)/);
  assert.match(searchUseCase, /if \(adminSearchOrder != null\) \{[\s\S]*?configuredOrder\.mapNotNull[\s\S]*?\} else \{/);
  const discovery = read(`${extensionDir}/usecase/DiscoverManagedServersUseCase.kt`);
  const episodes = read(`${extensionDir}/usecase/GetManagedEpisodesUseCase.kt`);
  assert.match(discovery, /searchOrderRepository: SearchOrderRepository/);
  assert.match(discovery, /if \(searchOrder == null\) \{[\s\S]*?eligible[\s\S]*?\} else \{[\s\S]*?getOrderForContentType\(targetContentType\)/);
  assert.match(episodes, /searchOrderRepository: SearchOrderRepository/);
  assert.match(episodes, /if \(searchOrder == null\) \{[\s\S]*?eligible\.firstOrNull\(\)[\s\S]*?\} else \{/);
});

test('empty or deleted remote SearchOrder is marked and cannot be replaced by stale cache', () => {
  const repository = read(`${extensionDir}/searchorder/SearchOrderRepository.kt`);
  const datasource = read(`${extensionDir}/searchorder/SearchOrderDataSource.kt`);
  const syncManager = read(`${extensionDir}/repository/ManagedExtensionRealtimeSyncManager.kt`);
  const cache = read(`${extensionDir}/repository/ManagedExtensionCache.kt`);
  const registry = read(`${extensionDir}/registry/ManagedExtensionRuntimeRegistry.kt`);
  assert.match(repository, /registryOrder\.updatedAt != null/);
  assert.match(datasource, /updatedAt = updatedAt \?: System\.currentTimeMillis\(\)/);
  assert.match(syncManager, /search_order deleted; empty order committed/);
  assert.match(syncManager, /updatedAt = when \(val rawUpdatedAt = data\["updatedAt"\]\)/);
  assert.match(syncManager, /private fun nextRemoteRevision\(sourceRevision: Long\? = null\)/);
  assert.match(syncManager, /revision = nextRemoteRevision\(searchOrder\.updatedAt\)/);
  assert.match(cache, /!searchOrder\.isEmpty \|\| searchOrder\.updatedAt != null/);
  assert.match(registry, /!lkg\.searchOrder\.isEmpty \|\| lkg\.searchOrder\.updatedAt != null/);
});

test('stream extraction and fallback enforce the remotely configured VIDEO_EXTRACTION capability', () => {
  assert.match(runtime, /contains\(ScraperCapability\.VIDEO_EXTRACTION\)/);
  assert.match(runtime, /allowsCapability\(ScraperCapability\.VIDEO_EXTRACTION/);
  assert.match(runtime, /ext\.status == ExtensionLifecycleStatus\.ACTIVE &&[\s\S]*?ext\.enabled/);
  assert.match(fallback, /ext\.status != ExtensionLifecycleStatus\.ACTIVE \|\| !ext\.enabled \|\| !ext\.userEnabled/);
});

test('comments feature code and explicit Firestore path are removed while unknown paths remain denied', () => {
  const appJava = path.join(root, 'app/src/main/java');
  const appRes = path.join(root, 'app/src/main/res');
  const runtimeFiles = [...filesUnder(appJava), ...filesUnder(appRes)]
    .filter(file => /\.(kt|xml)$/.test(file));
  for (const file of runtimeFiles) {
    const source = fs.readFileSync(file, 'utf8');
    assert.doesNotMatch(source, /comments_feature_disabled|commentsItem|isCommentAllowed|enforceCanComment|getCommentRestrictionMessage|canComment/i, path.relative(root, file));
  }
  assert.doesNotMatch(rules, /match\s+\/comments(?:\/|\s*\{)|canComment/i);
  assert.match(rules, /match\s*\/\{document=\*\*\}\s*\{\s*allow\s+read\s*,\s*write\s*:\s*if\s+false\s*;/s);
});

 test('the three category screens retain the lifecycle correction and no comment UI resources remain', () => {
  const screens = [
    'app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt',
    'app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt',
    'app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt',
  ];
  for (const file of screens) {
    const source = read(file);
    assert.match(source, /rememberUpdatedState/);
    assert.match(source, /DisposableEffect\(lifecycleOwner\)/);
  }
  for (const file of filesUnder(path.join(root, 'app/src/main/res')).filter(file => /\.xml$/.test(file))) {
    assert.doesNotMatch(fs.readFileSync(file, 'utf8'), /comments_feature_disabled/i, path.relative(root, file));
  }
});

test('managed extension fetch never treats an empty device cache as the authoritative cloud catalog', () => {
  const source = read(`${extensionDir}/repository/FirebaseFirestoreManagedExtensionDataSource.kt`);
  assert.match(source, /collection\(COLLECTION_PATH\)\.get\(Source\.SERVER\)\.await\(\)/);
  assert.match(source, /Read \/managed_extensions from server/);
  const orderSource = read(`${extensionDir}/searchorder/SearchOrderDataSource.kt`);
  assert.match(orderSource, /document\(SEARCH_ORDER_DOC\)\s*\.get\(Source\.SERVER\)\s*\.await\(\)/);
  assert.match(dto, /doc\.getString\("extensionId"\)/);
  assert.match(dto, /map\["extensionId"\]/);
});

test('realtime Firestore listeners wait for restored Firebase Auth and can recover after sign-in/token refresh', () => {
  assert.match(sync, /FirebaseAuth\.AuthStateListener/);
  assert.match(sync, /FirebaseAuth\.IdTokenListener/);
  assert.match(sync, /auth\.addAuthStateListener\(stateObserver\)/);
  assert.match(sync, /auth\.addIdTokenListener\(tokenObserver\)/);
  assert.match(sync, /if \(auth\.currentUser == null\)/);
  assert.match(sync, /Authenticated Firestore listeners attached/);
  assert.match(sync, /firestoreListenersAttached\.set\(false\)/);
});

test('Extensions page follows the selected locale and removes its redundant inner title/back bar', () => {
  const screen = read('app/src/main/java/com/example/ui/screens/extensions/ExtensionsScreen.kt');
  const route = read('app/src/main/java/com/example/navigation/AppNavigation.kt');
  const en = read('app/src/main/res/values/strings.xml');
  const ar = read('app/src/main/res/values-ar/strings.xml');
  assert.doesNotMatch(screen, /[\u0600-\u06FF]/);
  assert.doesNotMatch(screen, /TopAppBar|ArrowBack|onBackClick/);
  assert.match(screen, /stringResource\(R\.string\.search_order_tab\)/);
  assert.match(screen, /stringResource\(R\.string\.extensions_tab_count, managedExtensions\.size\)/);
  assert.match(route, /ExtensionsScreen\(\)/);
  for (const key of [
    'extensions_tab_count', 'search_order_tab', 'managed_extensions_security_title',
    'managed_extensions_security_description', 'managed_extensions_empty',
    'managed_extensions_unavailable', 'search_order_info_title', 'search_order_info_description',
    'search_order_anime', 'search_order_movies', 'search_order_series', 'search_order_tv',
    'search_order_arabic_dubbed', 'search_order_english_dubbed', 'search_order_source_count',
    'managed_extension_status_active', 'managed_extension_status_maintenance',
    'managed_extension_status_disabled', 'managed_extension_status_deprecated',
    'managed_extension_priority', 'managed_extension_version'
  ]) {
    assert.match(en, new RegExp(`<string name="${key}">`), `English resource ${key} missing`);
    assert.match(ar, new RegExp(`<string name="${key}">`), `Arabic resource ${key} missing`);
  }
  assert.match(en, /<string name="search_order_tab">Search Order<\/string>/);
  assert.match(ar, /<string name="search_order_tab">ترتيب البحث<\/string>/);
});



test('search-order-only local cache cannot initialize an empty Extensions catalog', () => {
  const registry = read(`${extensionDir}/registry/ManagedExtensionRuntimeRegistry.kt`);
  assert.match(registry, /val restoredExtensions = lkg\.extensions\.ifEmpty\s*\{[\s\S]*?BUNDLED_DEFAULT_EXTENSIONS/);
  assert.match(registry, /extensions = restoredExtensions/);
  assert.match(registry, /state = if \(waitingForRemoteCatalog\) GlobalExtensionConfigState\.REMOTE_SYNC_PENDING else GlobalExtensionConfigState\.READY/);
  assert.match(sync, /!snapshot\.exists\(\) && snapshot\.metadata\.isFromCache/);
  assert.match(sync, /Ignoring cache-only missing search_order; awaiting server snapshot/);
});

test('Admin maintenance/deprecated statuses survive enabled=false while active disabled entries remain disabled', () => {
  const normalizedStatusBranches = (dto.match(/normalizedStatus == "MAINTENANCE" \|\| normalizedStatus == "DEPRECATED" \|\| normalizedStatus == "DISABLED"/g) || []).length;
  assert.equal(normalizedStatusBranches, 2, 'DocumentSnapshot and Map parsers must preserve non-active lifecycle states');
  assert.match(dto, /normalizedStatus == "ACTIVE" && isEnabled == false -> "DISABLED"/);
  assert.match(mapper, /ExtensionLifecycleStatus\.valueOf\(normalized\)/, "normalized Admin lifecycle statuses must be mapped to the domain enum");
  assert.match(mapper, /ExtensionLifecycleStatus\.MAINTENANCE/, "unknown lifecycle states must remain safely non-executable");
});
