from pathlib import Path
import re, xml.etree.ElementTree as ET, zipfile, hashlib

root = Path('/mnt/data/phase39_redelivery/CINESTREAM-PRO')
flix = Path('/mnt/data/phase39_sources/flixquest/flixquest-main')
checks = []
def check(name, condition, details=''):
    checks.append((name, bool(condition), details))

# 1) All Android XML resources parse and locale files have unique string ids.
xmls = list((root/'app/src/main/res').rglob('*.xml'))
xml_errors = []
for p in xmls:
    try: ET.parse(p)
    except Exception as e: xml_errors.append(f'{p.relative_to(root)}: {e}')
check('All Android resource XML parses', not xml_errors, f'{len(xmls)} files; errors={len(xml_errors)}')
if xml_errors: print('\n'.join(xml_errors))
for locale in ('values', 'values-ar'):
    p = root/f'app/src/main/res/{locale}/strings.xml'
    tree = ET.parse(p)
    strings = tree.getroot().findall('string')
    names = [e.attrib.get('name') for e in strings]
    vals = {e.attrib.get('name'): e.text or '' for e in strings}
    dupes = sorted({n for n in names if names.count(n) > 1})
    check(f'{locale} has unique strings', not dupes, f'duplicates={dupes[:5]}')
    check(f'{locale} has premiere date labels', all(k in vals and '%1$s' in vals[k] for k in ('coming_date','premieres_date')), str({k:vals.get(k) for k in ('coming_date','premieres_date')}))

# 2) Search categories: compare actual FlixQuest dimensions to CineStream.
fq_search = (flix/'lib/mobile/screens/search_screen.dart').read_text()
cs_search = (root/'app/src/main/java/com/example/ui/screens/search/SearchScreen.kt').read_text()
fq_genres = fq_search[fq_search.index('class _GenreTiles'):fq_search.index('/// People who match')]
cs_genres = cs_search[cs_search.index('private fun GenreTilesSection'):cs_search.index('private fun SearchResultFilters')]
check('Search genre card height matches FlixQuest (52)', 'height: 52' in fq_genres and '.height(52.dp)' in cs_genres)
check('Search genre card gap, inset and font match', 'spacing = 10.0' in fq_genres and 'horizontal: 14' in fq_genres and 'fontSize: 15' in fq_genres and 'val gap = 10.dp' in cs_genres and '.padding(horizontal = 14.dp)' in cs_genres and 'fontSize = 15.sp' in cs_genres)
check('Search genre columns respond phone/tablet', 'width >= AppBreakpoints.tablet ? 4 : 2' in fq_genres and 'maxWidth >= 700.dp) 4 else 2' in cs_genres)
check('Search retains Movie, Series and Anime genre sections', all(x in cs_search for x in ('movie_genres_section','series_genres_section','anime_genres_section')))

# 3) Discover providers: compare ordered provider names and IDs from source catalog.
fq_query = (flix/'lib/catalog/discover_query.dart').read_text()
fq_block = re.search(r'static const services = <DiscoverOption>\[(.*?)\];', fq_query, re.S).group(1)
fq_services = re.findall(r"label:\s*'([^']+)',\s*value:\s*'([^']+)'", fq_block)
cs_comp = (root/'app/src/main/java/com/example/ui/screens/home/FlixQuestHomeComponents.kt').read_text()
cs_block = re.search(r'internal val homeStreamingServices = listOf\((.*?)\n\)', cs_comp, re.S).group(1)
cs_services = [(name, sid) for sid, name in re.findall(r'HomeStreamingService\((\d+),\s*"([^"]+)"', cs_block)]
check('Discover provider names/order/TMDB IDs equal FlixQuest', fq_services == cs_services, f'FlixQuest={fq_services}; CineStream={cs_services}')
cs_discover = (root/'app/src/main/java/com/example/ui/screens/home/DiscoverScreen.kt').read_text()
cs_srv = cs_discover[cs_discover.index('DiscoverSection(stringResource(R.string.streaming_on))'):cs_discover.index('DiscoverSection(stringResource(R.string.year)')]
fq_discover = (flix/'lib/mobile/screens/discover_screen.dart').read_text()
fq_srv = fq_discover[fq_discover.index('class _ServiceGrid'):fq_discover.index('class _YearRail')]
check('Discover streaming layout is 3 columns phone / 5 tablet', 'final columns = wide ? 5 : 3' in fq_srv and 'val columns = if (maxWidth >= 700.dp) 5 else 3' in cs_srv)
check('Discover streaming tile ratio equals 0.58', 'height: width * .58' in fq_srv and 'height(tileWidth * 0.58f)' in cs_srv)
check('Discover logo plate and fit behavior retained', 'AppPalette.logoPlate' in fq_srv and 'Color(0xFFF2F2F3)' in cs_srv and 'ContentScale.Fit' in cs_srv)
check('Discover provider selection keeps provider IDs and check feedback', 'selected.contains(service.value)' in fq_srv and 'filters.providers = if (selected)' in cs_srv and 'provider.providerId' in cs_srv and 'Icons.Default.Check' in cs_srv)

# 4) New & Hot / Coming Soon: compare structure, date grouping, card, artwork and list action.
fq_hot = (flix/'lib/mobile/screens/new_and_hot_screen.dart').read_text()
cs_hot = (root/'app/src/main/java/com/example/ui/screens/home/NewAndHotScreen.kt').read_text()
check('Coming Soon has a 68px date rail adjacent to card', 'width: 68' in fq_hot and 'Modifier.width(68.dp)' in cs_hot and 'ComingSoonFeatureCard' in cs_hot)
check('Coming Soon uses 16:9 backdrop and title logo fallback', 'aspectRatio: 16 / 9' in fq_hot and '.aspectRatio(16f / 9f)' in cs_hot and 'HotTitleLogoCache.resolve' in cs_hot and 'text = item.title' in cs_hot)
check('Coming Soon metadata and My List are implemented', 'premieres_date' in cs_hot and 'HotMyListButton(item)' in cs_hot and '_MyListButton(item: item)' in fq_hot)
check('Coming Soon has no Play control in its card', 'ComingSoonFeatureCard' in cs_hot and 'HotPlayButton' not in cs_hot[cs_hot.index('private fun ComingSoonFeatureCard'):cs_hot.index('private fun HotFeatureCard')])
check('Anime stays a distinct Upcoming type', 'state.upcomingAnime.forEach' in cs_hot and 'ContentType.ANIME' in cs_hot and 'TmdbGenreHelper.getAnimeGenres(language)' in cs_hot)
check('New & Hot tabs include anime Top 10', 'HotSection.TOP_ANIME' in cs_hot and 'state.upcomingAnime.forEach' in cs_hot)

passed = sum(ok for _,ok,_ in checks)
for name, ok, details in checks:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f' — {details}' if details else ''))
print(f'\nSUMMARY: {passed}/{len(checks)} passed')
if passed != len(checks): raise SystemExit(1)
