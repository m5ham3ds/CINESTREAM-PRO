const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');

const retrofit = read('app/src/main/java/com/example/data/remote/RetrofitClient.kt');
const paginator = read('app/src/main/java/com/example/data/repository/TmdbListPaginator.kt');
const repository = read('app/src/main/java/com/example/data/repository/TmdbMediaRepositoryImpl.kt');
const mediaCard = read('app/src/main/java/com/example/ui/components/MediaCard.kt');
const navigation = read('app/src/main/java/com/example/navigation/AppNavigation.kt');

test('TMDB requests do not force cache-only misses and 429/5xx responses are not cached', () => {
  assert.doesNotMatch(retrofit, /\.header\("Cache-Control",\s*"[^"]*only-if-cached/i);
  assert.match(retrofit, /response\.code == 429[\s\S]*?header\("Cache-Control",\s*"no-store"\)/);
  assert.match(retrofit, /response\.code >= 500[\s\S]*?header\("Cache-Control",\s*"no-store"\)/);
  assert.match(retrofit, /while\s*\(true\)[\s\S]*?earliestAllowedAt[\s\S]*?gatewayRateLimitedUntilMs/);
  assert.match(retrofit, /if \(response\.code == 429\)[\s\S]*?response\.close\(\)[\s\S]*?chain\.proceed\(authenticatedRequest\)/);
});

test('paginated catalog protects against unbounded load-more retries after an error', () => {
  assert.match(paginator, /state\.loadMoreError != null/);
  assert.match(paginator, /The catalog server is temporarily busy/);
  assert.match(paginator, /No cached response is available/);
});

test('Coming Soon and New Releases use distinct future versus already-released date windows', () => {
  assert.match(repository, /dateWindowCacheKey\("upcoming_movies", tomorrowIso\(\), "future"\)/);
  assert.match(repository, /dateWindowCacheKey\("new_releases_movies", recentStartIso\(\), todayIso\(\)\)/);
  assert.match(repository, /date >= start/);
  assert.match(repository, /releaseDateGte = start[\s\S]*?releaseDateLte = today/);
  assert.match(repository, /firstAirDateGte = start[\s\S]*?firstAirDateLte = today/);
  assert.doesNotMatch(repository, /\.toDomainMovies\(suppressAllRatings = true\)/);
  assert.doesNotMatch(repository, /\.toDomainSeries\(suppressAllRatings = true\)/);
  assert.match(repository, /if \(!score\.isFinite\(\) \|\| score !in 0\.0\.\.10\.0 \|\| \(voteCount \?: 0\) <= 0\) return 0\.0/);
  assert.match(read('app/src/main/java/com/example/ui/screens/home/UpcomingScreen.kt'), /rating = if \(isMovie\)[\s\S]*?\.rating/);
  assert.match(read('app/src/main/java/com/example/ui/screens/home/HomeScreen.kt'), /\/\/ 7\. Coming Soon[\s\S]*?rating = item\.rating/);
  assert.match(read('app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt'), /\/\/ Section: Coming Soon Movies[\s\S]*?rating = movie\.rating/);
  assert.match(read('app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt'), /\/\/ Coming Soon Series[\s\S]*?rating = series\.rating/);
  assert.match(read('app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt'), /\/\/ Coming Soon Anime[\s\S]*?rating = series\.rating/);
  assert.match(mediaCard, /showZeroRating: Boolean = false/);
  assert.match(mediaCard, /showZeroRating && it == 0\.0/);
  for (const file of [
    'app/src/main/java/com/example/ui/screens/home/UpcomingScreen.kt',
    'app/src/main/java/com/example/ui/screens/home/HomeScreen.kt',
    'app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt',
    'app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt',
    'app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt',
  ]) {
    assert.match(read(file), /showZeroRating = true/ , `${file} should show 0.0 when no verified rating exists`);
  }
});

test('secondary category tabs can toggle back to the page primary feed', () => {
  const cases = [
    ['app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt', 'MoviesNavCategory.MOVIES'],
    ['app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt', 'SeriesNavCategory.SERIES'],
    ['app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt', 'AnimeNavCategory.ANIME'],
  ];
  for (const [file, primary] of cases) {
    const source = read(file);
    assert.match(source, /if \(category == selectedCategory && category !=/);
    assert.ok(source.includes(`selectedCategory = ${primary}`), `${file} should return to its primary category`);
    assert.match(source, /viewModel\.selectGenre\(null\)/);
    assert.match(source, /gridPaginationArmed[\s\S]*?userScrolling && gridPaginationArmed/);
  }
});

test('home catalogue screens have scroll-armed pagination rather than continuous end-of-list loops', () => {
  for (const file of ['TrendingScreen.kt', 'PopularScreen.kt', 'UpcomingScreen.kt', 'NewReleasesScreen.kt']) {
    const source = read(`app/src/main/java/com/example/ui/screens/home/${file}`);
    assert.match(source, /var paginationArmed by remember\(page, selectedGenre\?\.id\)/, file);
    assert.match(source, /userScrolling && paginationArmed/, file);
    assert.match(source, /tabState\.loadMoreError == null/, file);
    assert.match(source, /genreAutoFillPages < 3/, file);
  }
});

test('community entry and routes require a registered non-anonymous Firebase account', () => {
  assert.match(navigation, /val hasRealCommunityAccount = !isGuest &&[\s\S]*?currentUser\?\.isAnonymous == false/);
  assert.match(navigation, /if \(hasRealCommunityAccount\)[\s\S]*?Screen\.Social\.route/);
  assert.match(navigation, /composable\(Screen\.Social\.route\)[\s\S]*?if \(hasRealCommunityAccount\)/);
  assert.match(navigation, /LoginRequiredDialog\([\s\S]*?onNavigateToAuth/);
  assert.match(read('app/src/main/java/com/example/ui/screens/social/SocialScreen.kt'), /!firebaseUser\.isAnonymous/);
});

test('manual filtered-page continuation is localized in English and Arabic', () => {
  assert.match(read('app/src/main/res/values/strings.xml'), /<string name="load_more_matches">Load more matching results<\/string>/);
  assert.match(read('app/src/main/res/values-ar/strings.xml'), /<string name="load_more_matches">تحميل المزيد من النتائج المطابقة<\/string>/);
});


test('Coming Soon movie queries enforce a future-only TMDB discover window and sanitize old cached date buckets', () => {
  assert.match(repository, /discoverMovies\([\s\S]*?sortBy = "primary_release_date\.asc"[\s\S]*?releaseDateGte = start/);
  assert.match(repository, /getUpcomingMoviesPage\(page: Int\)[\s\S]*?discoverMovies\([\s\S]*?releaseDateGte = start/);
  assert.match(repository, /sanitizeDateSeparatedCatalog\(cacheKey, cached\)/);
  assert.match(repository, /cacheKey\.startsWith\("upcoming_movies"\)[\s\S]*?isValidCachedDate\(it\.releaseDate, tomorrowIso\(\)\)/);
  assert.match(repository, /cacheKey\.startsWith\("new_releases_movies"\)[\s\S]*?isValidCachedDate\(it\.releaseDate, recentStartIso\(\), todayIso\(\)\)/);
});

test('paged feeds reuse relevant legacy disk cache offline but never substitute Popular for Top Rated', () => {
  assert.match(repository, /legacyCacheKeyForPagedNamespace/);
  assert.match(repository, /safePage == 1[\s\S]*?catalog_v5_\$\{language\}_\$\{it\}/);
  assert.match(repository, /pagecache_v1_[\s\S]*?loadPageMetadata/);
  assert.match(repository, /if \(namespace\.startsWith\("top_rated_"\)\) return null/);
  assert.match(read('app/src/main/java/com/example/utils/MediaListDiskCacheManager.kt'), /savePageMetadata[\s\S]*?loadPageMetadata/);
});

test('pull-to-refresh feedback auto-hides while the actual reload remains explicitly triggered', () => {
  const refreshHelper = read('app/src/main/java/com/example/ui/components/SkeletonScreens.kt');
  assert.match(refreshHelper, /rememberBriefPullRefreshIndicator/);
  assert.match(refreshHelper, /delay\(visibleMillis\.coerceAtLeast\(250L\)\)/);
  for (const file of [
    'app/src/main/java/com/example/ui/screens/home/HomeScreen.kt',
    'app/src/main/java/com/example/ui/screens/home/TrendingScreen.kt',
    'app/src/main/java/com/example/ui/screens/home/PopularScreen.kt',
    'app/src/main/java/com/example/ui/screens/home/NewReleasesScreen.kt',
    'app/src/main/java/com/example/ui/screens/home/UpcomingScreen.kt',
    'app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt',
    'app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt',
    'app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt',
    'app/src/main/java/com/example/ui/screens/search/SearchScreen.kt',
    'app/src/main/java/com/example/ui/screens/details/DetailsScreens.kt',
  ]) {
    const source = read(file);
    assert.match(source, /rememberBriefPullRefreshIndicator/ , `${file} should use transient pull feedback`);
    assert.match(source, /pullRefreshIndicator\.start\(\)/, `${file} must still invoke an actual reload`);
    assert.match(source, /isRefreshing = pullRefreshIndicator\.visible/ , `${file} should not tie indicator life to network latency`);
  }
});

test('crash reporting is isolated from the dying process and always has a non-blank fallback trace', () => {
  const application = read('app/src/main/java/com/example/MyApplication.kt');
  const crashActivity = read('app/src/main/java/com/example/ui/screens/crash/CrashActivity.kt');
  const manifest = read('app/src/main/AndroidManifest.xml');
  assert.match(manifest, /CrashActivity[\s\S]*?android:process=":crash_report"/);
  assert.match(application, /File\(filesDir, "last_crash\.txt"\)\.writeText\(stackTrace\)/);
  assert.match(application, /putString\("last_stack_trace", stackTrace\)/);
  assert.match(application, /processName\.endsWith\(":crash_report"\)\)\s*return/);
  assert.match(crashActivity, /Crash detected, but the original stack trace was unavailable/);
  assert.match(crashActivity, /\.takeIf \{ it\.isNotBlank\(\) \}/);
});

test('secondary category state resets on return and catalogue grid states provide skeleton/error recovery', () => {
  for (const [file, primary] of [
    ['app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt', 'MoviesNavCategory.MOVIES'],
    ['app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt', 'SeriesNavCategory.SERIES'],
    ['app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt', 'AnimeNavCategory.ANIME'],
    ['app/src/main/java/com/example/ui/screens/home/HomeScreen.kt', 'HomeNavCategory.HOME'],
  ]) {
    const source = read(file);
    assert.match(source, /Lifecycle\.Event\.ON_RESUME/ , `${file} should reset secondary tabs after returning`);
    assert.ok(source.includes(`selectedCategory = ${primary}`), `${file} should reset to ${primary}`);
  }
  const skeletons = read('app/src/main/java/com/example/ui/components/SkeletonScreens.kt');
  assert.match(skeletons, /fun CatalogGridSkeletonRows/);
  assert.match(skeletons, /fun CatalogGridStatusMessage/);
  const categoryScreens = [
    ['MoviesScreen.kt', 'movies', 'MoviesNavCategory.MOVIES', 'hasMovieContent'],
    ['SeriesScreen.kt', 'series', 'SeriesNavCategory.SERIES', 'hasSeriesContent'],
    ['AnimeScreen.kt', 'anime', 'AnimeNavCategory.ANIME', 'hasAnimeContent'],
  ];
  for (const [file, folder, primaryCategory, contentFlag] of categoryScreens) {
    const source = read(`app/src/main/java/com/example/ui/screens/${folder}/${file}`);
    assert.match(source, /CatalogGridSkeletonRows/ , `${file} should show skeletons before first-page results`);
    assert.match(source, /CatalogGridStatusMessage/ , `${file} should show an error or empty state with recovery`);
    assert.ok(source.includes(`if (!${contentFlag} && selectedCategory == ${primaryCategory})`),
      `${file} must not let an empty primary feed hide a secondary category's skeleton/error UI`);
  }
  const home = read('app/src/main/java/com/example/ui/screens/home/HomeScreen.kt');
  for (const media of ['Movies', 'Series', 'Anime']) {
    assert.match(home, new RegExp(`displayCategory${media}\\.isEmpty\\(\\) && \\(activeHomePageState\\.isLoadingFirstPage \\|\\| activeHomePageState\\.currentPage == 0\\) && activeHomePageState\\.loadMoreError == null`),
      `Home ${media} category must show a skeleton before its first page begins/resolves`);
  }
});
