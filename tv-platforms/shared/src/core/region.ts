/**
 * Country (ISO 3166 alpha-2, upper case) and language (ISO 639-1, lower case) of an entry.
 * Port of RegionTagger.kt: explicit tvg-country / tvg-language first, then category prefixes
 * ("FR| SPORT", "[AR] MBC", "UK: NEWS"), then country or language names written in the category.
 */
import { fold } from './text';

export interface Region {
  country: string | null;
  language: string | null;
}

const R = (country: string | null, language: string | null): Region => ({ country, language });

/** ISO 3166-1 alpha-2 codes (java.util.Locale.getISOCountries on Android). */
const COUNTRIES = new Set(
  ('AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ ' +
    'CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR ' +
    'GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP ' +
    'KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT ' +
    'MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW ' +
    'SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG ' +
    'UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW').split(' '),
);

const PREFIX_ALIASES: Record<string, Region> = {
  UK: R('GB', 'en'), USA: R('US', 'en'), KSA: R('SA', 'ar'), UAE: R('AE', 'ar'),
  AR: R(null, 'ar'), ARA: R(null, 'ar'), ARB: R(null, 'ar'), ARAB: R(null, 'ar'),
  EN: R(null, 'en'), KU: R(null, 'ku'), KRD: R(null, 'ku'), KURD: R(null, 'ku'), ENG: R(null, 'en'),
  FRA: R('FR', 'fr'), FRE: R(null, 'fr'), GER: R('DE', 'de'), DEU: R('DE', 'de'), ITA: R('IT', 'it'),
  ESP: R('ES', 'es'), SPA: R(null, 'es'), POR: R('PT', 'pt'), TUR: R('TR', 'tr'), NED: R('NL', 'nl'),
  LAT: R(null, 'es'), LATINO: R(null, 'es'),
  AFR: R(null, null), EXYU: R(null, null), VIP: R(null, null), HD: R(null, null), FHD: R(null, null),
  UHD: R(null, null), SD: R(null, null), TV: R(null, null), PPV: R(null, null), VOD: R(null, null), XXX: R(null, null),
};

const COUNTRY_LANGUAGE: Record<string, string> = {
  FR: 'fr', MC: 'fr', GB: 'en', US: 'en', IE: 'en', AU: 'en', NZ: 'en', DE: 'de', AT: 'de', IT: 'it', ES: 'es',
  MX: 'es', AR: 'es', CO: 'es', PT: 'pt', BR: 'pt', NL: 'nl', TR: 'tr', PL: 'pl', RO: 'ro', RU: 'ru', GR: 'el',
  SE: 'sv', NO: 'no', DK: 'da', FI: 'fi', CZ: 'cs', HU: 'hu', IR: 'fa', IL: 'he', IN: 'hi', PK: 'ur', AL: 'sq',
  RS: 'sr', HR: 'hr', MA: 'ar', DZ: 'ar', TN: 'ar', LY: 'ar', EG: 'ar', SA: 'ar', AE: 'ar', QA: 'ar', KW: 'ar',
  BH: 'ar', OM: 'ar', YE: 'ar', IQ: 'ar', SY: 'ar', LB: 'ar', JO: 'ar', PS: 'ar', SD: 'ar',
};

const WORDS: [string, Region][] = [
  ['france', R('FR', 'fr')], ['francais', R(null, 'fr')], ['french', R(null, 'fr')],
  ['belgique', R('BE', null)], ['belgium', R('BE', null)], ['suisse', R('CH', null)],
  ['canada', R('CA', null)], ['quebec', R('CA', 'fr')],
  ['maroc', R('MA', 'ar')], ['morocco', R('MA', 'ar')], ['algerie', R('DZ', 'ar')],
  ['algeria', R('DZ', 'ar')], ['tunisie', R('TN', 'ar')], ['tunisia', R('TN', 'ar')],
  ['egypt', R('EG', 'ar')], ['egypte', R('EG', 'ar')], ['liban', R('LB', 'ar')],
  ['lebanon', R('LB', 'ar')], ['syria', R('SY', 'ar')], ['syrie', R('SY', 'ar')],
  ['iraq', R('IQ', 'ar')], ['irak', R('IQ', 'ar')], ['saudi', R('SA', 'ar')],
  ['qatar', R('QA', 'ar')], ['kuwait', R('KW', 'ar')], ['emirates', R('AE', 'ar')],
  ['jordan', R('JO', 'ar')], ['palestine', R('PS', 'ar')],
  ['arabic', R(null, 'ar')], ['arabe', R(null, 'ar')], ['arab', R(null, 'ar')],
  ['عربي', R(null, 'ar')], ['عربية', R(null, 'ar')], ['المغرب', R('MA', 'ar')],
  ['الجزائر', R('DZ', 'ar')], ['تونس', R('TN', 'ar')], ['مصر', R('EG', 'ar')],
  ['لبنان', R('LB', 'ar')], ['سوريا', R('SY', 'ar')], ['العراق', R('IQ', 'ar')],
  ['السعودية', R('SA', 'ar')], ['الخليج', R(null, 'ar')],
  ['english', R(null, 'en')], ['united kingdom', R('GB', 'en')], ['usa', R('US', 'en')],
  ['germany', R('DE', 'de')], ['deutsch', R('DE', 'de')], ['allemagne', R('DE', 'de')],
  ['italia', R('IT', 'it')], ['italy', R('IT', 'it')], ['italie', R('IT', 'it')],
  ['espana', R('ES', 'es')], ['spain', R('ES', 'es')], ['espagne', R('ES', 'es')],
  ['portugal', R('PT', 'pt')], ['brasil', R('BR', 'pt')], ['brazil', R('BR', 'pt')],
  ['turkey', R('TR', 'tr')], ['turkiye', R('TR', 'tr')], ['turquie', R('TR', 'tr')],
  ['netherlands', R('NL', 'nl')], ['nederland', R('NL', 'nl')], ['pays-bas', R('NL', 'nl')],
  ['poland', R('PL', 'pl')], ['polska', R('PL', 'pl')], ['romania', R('RO', 'ro')],
  ['india', R('IN', 'hi')], ['pakistan', R('PK', 'ur')], ['iran', R('IR', 'fa')],
  ['kurdistan', R(null, 'ku')], ['kurdish', R(null, 'ku')], ['kurde', R(null, 'ku')],
  ['kurdi', R(null, 'ku')], ['kurd', R(null, 'ku')], ['کوردی', R(null, 'ku')],
  ['كوردي', R(null, 'ku')], ['كردي', R(null, 'ku')], ['كردستان', R(null, 'ku')], ['کوردستان', R(null, 'ku')],
  ['afrique', R(null, null)], ['africa', R(null, null)],
];

const LANGUAGE_NAMES: Record<string, string> = {
  french: 'fr', francais: 'fr', fra: 'fr', fre: 'fr',
  arabic: 'ar', arabe: 'ar', ara: 'ar', 'عربي': 'ar', 'العربية': 'ar',
  english: 'en', anglais: 'en', eng: 'en',
  german: 'de', deutsch: 'de', allemand: 'de', deu: 'de', ger: 'de',
  spanish: 'es', espanol: 'es', espagnol: 'es', spa: 'es',
  italian: 'it', italiano: 'it', italien: 'it', ita: 'it',
  portuguese: 'pt', portugues: 'pt', por: 'pt',
  turkish: 'tr', turkce: 'tr', tur: 'tr',
  dutch: 'nl', nederlands: 'nl', nld: 'nl',
  russian: 'ru', rus: 'ru', persian: 'fa', farsi: 'fa', hindi: 'hi', urdu: 'ur',
  kurdish: 'ku', kurdi: 'ku', kurmanji: 'ku', sorani: 'ku', kmr: 'ku', ckb: 'ku', kur: 'ku',
  berber: 'ber', amazigh: 'ber', tamazight: 'ber',
};

const PREFIX = /^[\s|[(]*([A-Za-z]{2,6})\s*(?:[|\]):-]|\s+-\s)/;
const isLetter = (c: string) => /\p{L}/u.test(c);

export function normalizeCountry(raw: string | null | undefined): string | null {
  const first = raw?.split(/[;,| ]/).find((s) => s.trim() !== '')?.trim().toUpperCase();
  if (!first) return null;
  const alias = PREFIX_ALIASES[first];
  if (alias?.country) return alias.country;
  return first.length === 2 && COUNTRIES.has(first) ? first : null;
}

export function normalizeLanguage(raw: string | null | undefined): string | null {
  const first = raw?.split(/[;,|]/).find((s) => s.trim() !== '')?.trim();
  if (!first) return null;
  const key = fold(first);
  if (LANGUAGE_NAMES[key]) return LANGUAGE_NAMES[key];
  return key.length === 2 && /^[a-z]{2}$/.test(key) ? key : null;
}

function prefix(group: string): Region | null {
  const m = PREFIX.exec(group);
  if (!m) return null;
  const code = m[1].toUpperCase();
  const alias = PREFIX_ALIASES[code];
  if (alias) return alias.country != null || alias.language != null ? alias : null;
  if (code.length === 2 && COUNTRIES.has(code)) return R(code, COUNTRY_LANGUAGE[code] ?? null);
  return null;
}

function words(group: string): Region | null {
  const g = fold(group);
  if (g === '') return null;
  for (const [word, region] of WORDS) {
    if (region.country == null && region.language == null) continue;
    const i = g.indexOf(word);
    if (i < 0) continue;
    const before = i === 0 ? ' ' : g[i - 1];
    const ai = i + word.length;
    const after = ai >= g.length ? ' ' : g[ai];
    if (!isLetter(before) && !isLetter(after)) return region;
  }
  return null;
}

export function tagRegion(group: string | null | undefined, tvgCountry?: string | null, tvgLanguage?: string | null): Region {
  let country = normalizeCountry(tvgCountry);
  let language = normalizeLanguage(tvgLanguage);
  if (country == null || language == null) {
    const g = group ?? '';
    const fromPrefix = prefix(g);
    const fromWords = fromPrefix?.country == null || fromPrefix?.language == null ? words(g) : null;
    country = country ?? fromPrefix?.country ?? fromWords?.country ?? null;
    language = language ?? fromPrefix?.language ?? fromWords?.language ?? null;
  }
  if (language == null && country != null) language = COUNTRY_LANGUAGE[country] ?? null;
  return R(country, language);
}
