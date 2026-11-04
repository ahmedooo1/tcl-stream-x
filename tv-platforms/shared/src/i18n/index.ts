/**
 * Texts of the app, French by default and Arabic, the same keys and wording as the Android app
 * (generated from its strings.xml), plus a few texts specific to the TV editions. Arabic switches
 * the whole layout to right-to-left, like the Android app.
 */
import { ar, fr } from './strings.gen';

export type Lang = 'fr' | 'ar';
export type Key = keyof typeof fr | keyof typeof tvFr;

/** Texts that only exist on the TV editions (Android equivalents use system screens). */
const tvFr = {
  tv_storage_note: 'Les identifiants sont chiffrés sur le téléviseur avec une clé non exportable. Voir la sécurité dans l\'aide.',
  tv_lan_unavailable: 'Envoi depuis le téléphone : non disponible sur ce téléviseur (pas de serveur local pour les applications web).',
  tv_pin_title: 'Code parental',
  tv_pin_enter: 'Saisissez le code à 4 chiffres',
  tv_pin_wrong: 'Code incorrect',
  tv_keyboard_hint: 'OK pour saisir · le clavier du téléviseur s\'ouvre',
  tv_loading: 'Chargement…',
  tv_platform: 'Édition',
  tv_updates_store: 'Les mises à jour de cette édition s\'installent depuis un ordinateur (mode développeur) : voir la page d\'installation.',
  tv_update_available: 'Version %1$s disponible',
  tv_no_results: 'Aucun résultat',
  tv_xtream_note: 'Utilisez uniquement un abonnement auquel vous avez droit. Le mot de passe est chiffré sur le téléviseur (clé WebCrypto non exportable), jamais affiché ni écrit dans les journaux.',
  tv_not_yet: 'Cette section n\'est pas encore disponible dans cette édition.',
  tv_blocked_local: 'Les fichiers de la TV et des clés USB ne sont pas accessibles aux applications web de ce téléviseur.',
  tv_import_running: 'Import en cours…',
  tv_pin_set: 'Code enregistré',
  tv_unlock: 'Déverrouiller',
  tv_set_pin: 'Créer un code',
  tv_update_where: 'Page de téléchargement (scannez le code avec le téléphone) : %1$s',
  tv_update_file: 'Fichier à installer : %1$s',
  tv_update_unsigned: 'Cette version n\'est pas encore signée pour votre téléviseur : signez-la avec votre profil Samsung (voir l\'aide d\'installation).',
  tv_update_how_samsung: 'Installation depuis l\'ordinateur : sdb connect <IP de la TV>, puis tizen install -n StreamX-Samsung.wgt. Vos playlists et réglages sont conservés.',
  tv_update_how_lg: 'Installation depuis l\'ordinateur : ares-install --device tv StreamX-LG.ipk. Vos playlists et réglages sont conservés.',
  tv_lan_import: 'Importer depuis le réseau local : tapez l\'adresse d\'un fichier M3U servi par un ordinateur ou un téléphone du réseau (par exemple http://192.168.1.20:8000/liste.m3u).',
  tv_external: 'Ouvrir dans un autre lecteur : non disponible pour les applications web de ce téléviseur.',
} as const;

const tvAr: Record<keyof typeof tvFr, string> = {
  tv_storage_note: 'بيانات الدخول مشفّرة على التلفاز بمفتاح لا يمكن تصديره. راجع قسم الأمان في المساعدة.',
  tv_lan_unavailable: 'الإرسال من الهاتف غير متاح على هذا التلفاز (لا يوجد خادم محلي لتطبيقات الويب).',
  tv_pin_title: 'رمز الحماية',
  tv_pin_enter: 'أدخل الرمز المكوّن من 4 أرقام',
  tv_pin_wrong: 'رمز غير صحيح',
  tv_keyboard_hint: 'اضغط OK للكتابة · تفتح لوحة مفاتيح التلفاز',
  tv_loading: 'جارٍ التحميل…',
  tv_platform: 'الإصدار',
  tv_updates_store: 'تُثبَّت تحديثات هذا الإصدار من كمبيوتر (وضع المطوّر): راجع صفحة التثبيت.',
  tv_update_available: 'الإصدار %1$s متوفر',
  tv_no_results: 'لا توجد نتائج',
  tv_xtream_note: 'استخدم فقط اشتراكًا يحق لك استخدامه. كلمة المرور مشفّرة على التلفاز (مفتاح WebCrypto لا يمكن تصديره)، ولا تُعرض ولا تُكتب في السجلات.',
  tv_not_yet: 'هذا القسم غير متوفر بعد في هذا الإصدار.',
  tv_blocked_local: 'ملفات التلفاز ومفاتيح USB غير متاحة لتطبيقات الويب على هذا التلفاز.',
  tv_import_running: 'جارٍ الاستيراد…',
  tv_pin_set: 'تم حفظ الرمز',
  tv_unlock: 'فتح القفل',
  tv_set_pin: 'إنشاء رمز',
  tv_update_where: 'صفحة التنزيل (امسح الرمز بالهاتف): %1$s',
  tv_update_file: 'الملف المطلوب تثبيته: %1$s',
  tv_update_unsigned: 'هذا الإصدار غير موقّع بعد لتلفازك: وقّعه بملف Samsung الخاص بك (راجع دليل التثبيت).',
  tv_update_how_samsung: 'التثبيت من الكمبيوتر: sdb connect <عنوان التلفاز> ثم tizen install -n StreamX-Samsung.wgt. تبقى قوائمك وإعداداتك محفوظة.',
  tv_update_how_lg: 'التثبيت من الكمبيوتر: ares-install --device tv StreamX-LG.ipk. تبقى قوائمك وإعداداتك محفوظة.',
  tv_lan_import: 'الاستيراد من الشبكة المحلية: اكتب عنوان ملف M3U يقدّمه كمبيوتر أو هاتف على الشبكة (مثلًا http://192.168.1.20:8000/liste.m3u).',
  tv_external: 'الفتح في مشغّل آخر غير متاح لتطبيقات الويب على هذا التلفاز.',
};

let lang: Lang = 'fr';
const listeners = new Set<() => void>();

export function setLang(l: Lang): void {
  lang = l;
  document.documentElement.lang = l;
  document.documentElement.dir = l === 'ar' ? 'rtl' : 'ltr';
  listeners.forEach((f) => f());
}

export const getLang = (): Lang => lang;
export const isRtl = (): boolean => lang === 'ar';
export const onLangChange = (f: () => void) => { listeners.add(f); return () => listeners.delete(f); };

/** Android-style formatting: %1$s, %2$d, %s, %d, %%. */
export function format(template: string, args: (string | number)[]): string {
  let auto = 0;
  return template.replace(/%(?:(\d+)\$)?([sd%])/g, (_m, idx: string | undefined, type: string) => {
    if (type === '%') return '%';
    const v = idx ? args[Number(idx) - 1] : args[auto++];
    if (v == null) return '';
    return type === 'd' && typeof v === 'number' ? String(Math.trunc(v)) : String(v);
  });
}

export function t(key: Key, ...args: (string | number)[]): string {
  const k = key as string;
  const raw = lang === 'ar'
    ? (ar as Record<string, string | undefined>)[k] ?? (tvAr as Record<string, string>)[k] ?? (fr as Record<string, string>)[k] ?? (tvFr as Record<string, string>)[k]
    : (fr as Record<string, string>)[k] ?? (tvFr as Record<string, string>)[k];
  return format(raw ?? k, args);
}

export const hasKey = (k: string): boolean => k in fr || k in tvFr;
