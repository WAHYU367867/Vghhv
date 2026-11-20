import { CategoryItem, ContentItem, Kinds, UserInfo } from '../types';
import { ArabicSearch } from '../utils/arabic';

/**
 * Isolated free catalog activated ONLY by the free credentials (Alhur / Alhur123).
 * Completely separated from the paid Xtream server: never sends free credentials to Xtream,
 * and never mixes free content into a paid Xtream session.
 */
export const FREE_USER = 'Alhur';
export const FREE_PASS = 'Alhur123';
export const FREE_SERVER = 'free://alhur.tv';

const FREE_URLS_STORAGE_KEY = 'alhorr_free_urls_v1';

interface FreeChannel {
  id: string;
  name: string;
  catId: string;
  catName: string;
  streamUrl: string;
  logoUrl?: string;
}

interface FreeMovie {
  id: string;
  title: string;
  categoryId: string;
  posterUrl: string;
  streamUrl: string;
  year: string;
  rating: string;
  duration: string;
  durationSecs: number;
  genre: string;
  director: string;
  cast: string;
  country: string;
  plot: string;
}

interface FreeEpisode {
  id: string;
  title: string;
  streamUrl: string;
  imageUrl: string;
  duration: string;
  durationSecs: number;
  plot: string;
}

interface FreeSeries {
  id: string;
  title: string;
  categoryId: string;
  posterUrl: string;
  year: string;
  rating: string;
  genre: string;
  cast: string;
  plot: string;
  episodes: FreeEpisode[];
}

export function isFreeCredentials(username?: string | null, password?: string | null): boolean {
  return (
    (username ?? '').trim().toLowerCase() === FREE_USER.toLowerCase() &&
    (password ?? '').trim().toLowerCase() === FREE_PASS.toLowerCase()
  );
}

export function isFreeServer(server?: string | null): boolean {
  return (server ?? '').trim().toLowerCase().startsWith('free://');
}

export function resolveServer(
  savedServer: string | null | undefined,
  username: string | null | undefined,
  password: string | null | undefined,
  defaultPaidServer: string
): string {
  if (isFreeCredentials(username, password)) return FREE_SERVER;
  const trimmed = (savedServer ?? '').trim();
  return trimmed && !isFreeServer(trimmed) ? trimmed : defaultPaidServer;
}

export function freeAccountInfo(): {
  user_info: UserInfo;
  server_info: Record<string, unknown>;
  auth: number;
} {
  return {
    user_info: {
      username: FREE_USER,
      status: 'Active',
      auth: 1,
      exp_date: '0',
      is_trial: '0',
      active_cons: '1',
      max_connections: 'مفتوح',
    },
    server_info: {
      url: 'free.alhur.tv',
      timezone: 'Asia/Riyadh',
    },
    auth: 1,
  };
}

// Verified HTTP-200 free-to-air Arabic TV channels (offline fallback when M3U fetch fails)
const BUILTIN_CHANNELS: FreeChannel[] = [
  // News
  { id: '919001', name: 'الجزيرة أخبار 2', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://live-hls-web-aja2-gcp.thehlive.com/AJA2/index.m3u8' },
  { id: '919002', name: 'العربية الحدث', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://shd-gcp-live.edgenextcdn.net/live/bitmovin-hadath/2ff87ec4c2f3ede35295a20637d9f8fd/index.m3u8' },
  { id: '919003', name: 'الإخبارية السعودية', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://shd-gcp-live.edgenextcdn.net/live/bitmovin-al-ekhbaria/297b3ef1cd0633ad9cfba7473a686a06/index.m3u8' },
  { id: '919004', name: 'التلفزيون العربي', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://live.kwikmotion.com/alaraby1live/alaraby_abr/playlist.m3u8' },
  { id: '919005', name: 'العربي 2', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://live.kwikmotion.com/alaraby2live/alaraby2.smil/playlist.m3u8' },
  { id: '919006', name: 'سكاي نيوز عربية', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://live-stream.skynewsarabia.com/c-horizontal-channel/horizontal-stream/index.m3u8' },
  { id: '919007', name: 'فرانس 24 عربية', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://live.france24.com/hls/live/2037222-b/F24_AR_HI_HLS/master_5000.m3u8' },
  { id: '919008', name: 'العربية بيزنس', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://live.alarabiya.net/alarabiapublish/aswaaq.smil/playlist.m3u8' },
  { id: '919009', name: 'CNBC عربية', catId: 'fc_live_news', catName: 'أخبار عربية واقتصاد', streamUrl: 'https://cnbc-live.akamaized.net/cnbc/master.m3u8' },

  // General & Entertainment
  { id: '919010', name: 'MBC 1', catId: 'fc_live_general', catName: 'قنوات عربية عامة', streamUrl: 'https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-1/15cf99af5de54063fdabfefe66adc075/index.m3u8' },
  { id: '919011', name: 'MBC 4', catId: 'fc_live_general', catName: 'قنوات عربية عامة', streamUrl: 'https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-4/24f134f1cd63db9346439e96b86ca6ed/index.m3u8' },
  { id: '919012', name: 'MBC دراما', catId: 'fc_live_general', catName: 'قنوات عربية عامة', streamUrl: 'https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-drama/2c28a458e2f3253e678b07ac7d13fe71/index.m3u8' },
  { id: '919013', name: 'LBC الفضائية', catId: 'fc_live_general', catName: 'قنوات عربية عامة', streamUrl: 'http://185.9.2.18/chid_327/index.m3u8' },

  // Movies & Series channels
  { id: '919014', name: 'قناة أفلام', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://shd-amg-fast.edgenextcdn.net/tx001/playlist.m3u8' },
  { id: '919015', name: 'ميكس هوليوود', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://ml-pull-hwc.myco.io/MixTV/hls/index.m3u8' },
  { id: '919016', name: 'أفلام أكشن', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://shd-amg-fast.edgenextcdn.net/tx011/playlist.m3u8' },
  { id: '919017', name: 'أفلام إثارة', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://shd-amg-fast.edgenextcdn.net/tx012/playlist.m3u8' },
  { id: '919018', name: 'آي فيلم عربية', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://live.presstv.co.uk/hls/ifilmar.m3u8' },
  { id: '919019', name: 'MBC مصر دراما', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-drama/567b703c19ede6598222de81b0e4508b/index.m3u8' },
  { id: '919020', name: 'MBC+ دراما', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-plus-drama/e37251ec2aac8f6c98f75cd0fa37cd28/index.m3u8' },
  { id: '919021', name: 'باب الحارة', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://shd-amg-fast.edgenextcdn.net/tx010/playlist.m3u8' },
  { id: '919022', name: 'مرايا', catId: 'fc_live_movies', catName: 'أفلام ومسلسلات', streamUrl: 'https://shd-amg-fast.edgenextcdn.net/tx008/playlist.m3u8' },

  // Sports
  { id: '919023', name: 'الكويت الرياضية', catId: 'fc_live_sports', catName: 'رياضة عربية', streamUrl: 'https://kwtspta.cdn.mangomolo.com/sp/smil:sp.stream.smil/chunklist.m3u8' },
  { id: '919024', name: 'الرياضية المغربية', catId: 'fc_live_sports', catName: 'رياضة عربية', streamUrl: 'http://149.100.11.252:8000/play/a065/index.m3u8' },
  { id: '919025', name: 'العراقية الرياضية', catId: 'fc_live_sports', catName: 'رياضة عربية', streamUrl: 'https://imn-live.esite-lab.com/hls/iraqia-sports-1.m3u8' },
  { id: '919026', name: 'البحرين الرياضية 1', catId: 'fc_live_sports', catName: 'رياضة عربية', streamUrl: 'https://5c7b683162943.streamlock.net/live/ngrp:sportsone_all/playlist.m3u8' },

  // Kids
  { id: '919027', name: 'سبيستون عربية', catId: 'fc_live_kids', catName: 'أطفال وكرتون', streamUrl: 'https://live-uae-next.spacetoongo.com/ST_MENA_NEXT/hls/r9p2hjipmw2kl.m3u8' },
  { id: '919028', name: 'قناة طه للأطفال', catId: 'fc_live_kids', catName: 'أطفال وكرتون', streamUrl: 'https://stream.starmenajo.com/hls/app/live/ts:fhd.m3u8' },
  { id: '919029', name: 'أطفال ومواهب', catId: 'fc_live_kids', catName: 'أطفال وكرتون', streamUrl: 'https://5aafcc5de91f1.streamlock.net/atfal1.com/atfal2/playlist.m3u8' },

  // Quran & Religious
  { id: '919030', name: 'القرآن الكريم - مكة', catId: 'fc_live_religious', catName: 'قرآن ودينية', streamUrl: 'http://m.live.net.sa:1935/live/quran/gmswf.m3u8' },
  { id: '919031', name: 'السنة النبوية - المدينة', catId: 'fc_live_religious', catName: 'قرآن ودينية', streamUrl: 'http://m.live.net.sa:1935/live/sunnah/gmswf.m3u8' },
  { id: '919032', name: 'تلفزيون مكة', catId: 'fc_live_religious', catName: 'قرآن ودينية', streamUrl: 'https://media2.streambrothers.com:1936/8122/8122/playlist.m3u8' },
  { id: '919033', name: 'قناة اقرأ', catId: 'fc_live_religious', catName: 'قرآن ودينية', streamUrl: 'https://playlist.fasttvcdn.com/pl/dlkqw1ftuvuuzkcb4pxdcg/Iqraafasttv1/playlist.m3u8' },
  { id: '919034', name: 'القرآن الكريم - قطر', catId: 'fc_live_religious', catName: 'قرآن ودينية', streamUrl: 'https://qatartv.akamaized.net/hls/live/20000612/qtvquran/master.m3u8' },

  // Documentary
  { id: '919035', name: 'الشرق ديسكفري', catId: 'fc_live_doc', catName: 'وثائقيات وثقافة', streamUrl: 'https://svs.itworkscdn.net/asharqdiscoverylive/asharqd.smil/playlist_dvr.m3u8' },
  { id: '919036', name: 'الشرق الوثائقية', catId: 'fc_live_doc', catName: 'وثائقيات وثقافة', streamUrl: 'https://svs.itworkscdn.net/asharqdocumentarylive/asharqdocumentary.smil/playlist_dvr.m3u8' },
  { id: '919037', name: 'برامج العربية', catId: 'fc_live_doc', catName: 'وثائقيات وثقافة', streamUrl: 'https://live.alarabiya.net/alarabiapublish/aaprograms.smil/playlist.m3u8' },
];

const MOVIE_CATEGORIES: Array<{ id: string; name: string }> = [
  { id: 'fc_mov_anim', name: 'رسوم متحركة وعائلي' },
  { id: 'fc_mov_action', name: 'أكشن ومغامرات' },
  { id: 'fc_mov_comedy', name: 'كوميديا كلاسيكية' },
  { id: 'fc_mov_mystery', name: 'غموض وتشويق' },
];

const FREE_MOVIES: FreeMovie[] = [
  {
    id: '920001',
    title: 'سينتل — رحلة التنين (Sintel)',
    categoryId: 'fc_mov_anim',
    posterUrl: 'https://archive.org/services/img/Sintel',
    streamUrl: 'https://archive.org/download/Sintel/sintel-2048-stereo.mp4',
    year: '2010',
    rating: '8.4',
    duration: '15 د',
    durationSecs: 888,
    genre: 'فانتازيا، مغامرة، رسوم متحركة',
    director: 'كولين ليفي',
    cast: 'هالينا راين، ثوم هوفمان',
    country: 'هولندا',
    plot: 'فتاة وحيدة تدعى سينتل تنطلق في رحلة شاقة عبر الجبال والصحارى للبحث عن التنين الصغير الذي اعتنت به وصار صديقها الوحيد.',
  },
  {
    id: '920002',
    title: 'بيغ باك باني (Big Buck Bunny)',
    categoryId: 'fc_mov_anim',
    posterUrl: 'https://archive.org/services/img/BigBuckBunny_328',
    streamUrl: 'https://archive.org/download/BigBuckBunny_328/BigBuckBunny_512kb.mp4',
    year: '2008',
    rating: '8.1',
    duration: '10 د',
    durationSecs: 596,
    genre: 'رسوم متحركة، كوميديا، عائلي',
    director: 'ساشا غويدجلب',
    cast: 'إنتاج استوديو بليندر المفتوح',
    country: 'هولندا',
    plot: 'أرنب طيب القلب يعيش بسلام في الغابة، لكن ثلاثة قوارض مشاغبة يعتدون على الفراشات، فيقرر الدفاع عن أصدقائه بخطة ذكية.',
  },
  {
    id: '920003',
    title: 'حلم الفيلة (Elephants Dream)',
    categoryId: 'fc_mov_anim',
    posterUrl: 'https://archive.org/services/img/ElephantsDream',
    streamUrl: 'https://archive.org/download/ElephantsDream/ed_hd.mp4',
    year: '2006',
    rating: '7.5',
    duration: '11 د',
    durationSecs: 654,
    genre: 'خيال علمي، رسوم متحركة',
    director: 'باسام كردلي',
    cast: 'تايغو جيرناندت، كاس يانسن',
    country: 'هولندا',
    plot: 'رحلة بصرية داخل عالم آلي عملاق حيث يستكشف إيمو وبروغ أسرار الآلة التي تحيط بهما.',
  },
  {
    id: '920004',
    title: 'كتاب الأدغال الكلاسيكي (Jungle Book)',
    categoryId: 'fc_mov_action',
    posterUrl: 'https://archive.org/services/img/JungleBook',
    streamUrl: 'https://archive.org/download/JungleBook/Jungle_Book.mp4',
    year: '1942',
    rating: '7.9',
    duration: '108 د',
    durationSecs: 6480,
    genre: 'مغامرات، عائلي، دراما',
    director: 'زولتان كوردا',
    cast: 'سابو، جوزيف كالييا، جون كوالين',
    country: 'الولايات المتحدة',
    plot: 'قصة ماوكلي الفتى الذي نشأ بين حيوانات الأدغال ثم عاد إلى قرية البشر ليكتشف الصراع بين طبيعة الغابة وجشع الذهب.',
  },
  {
    id: '920005',
    title: 'الجنرال — باستر كيتون (The General)',
    categoryId: 'fc_mov_action',
    posterUrl: 'https://archive.org/services/img/The_General_Buster_Keaton',
    streamUrl: 'https://archive.org/download/The_General_Buster_Keaton/The_General.mp4',
    year: '1926',
    rating: '8.7',
    duration: '78 د',
    durationSecs: 4680,
    genre: 'أكشن، مغامرات، كوميديا',
    director: 'باستر كيتون',
    cast: 'باستر كيتون، ماريون ماك',
    country: 'الولايات المتحدة',
    plot: 'مهندس قطارات شجاع يطارد بمفرده قاطرته البخارية المسروقة «الجنرال» وحبيبته خلف خطوط العدو في مغامرة مطاردات مذهلة.',
  },
  {
    id: '920006',
    title: 'فتاة الجمعة (His Girl Friday)',
    categoryId: 'fc_mov_comedy',
    posterUrl: 'https://archive.org/services/img/HisGirlFriday',
    streamUrl: 'https://archive.org/download/HisGirlFriday/MoviePowderPresentsHisGirlFriday.mp4',
    year: '1940',
    rating: '8.3',
    duration: '92 د',
    durationSecs: 5520,
    genre: 'كوميديا، دراما',
    director: 'هاوارد هوكس',
    cast: 'كاري غرانت، روزاليند راسل، رالف بيلامي',
    country: 'الولايات المتحدة',
    plot: 'رئيس تحرير صحيفة يحاول استعادة زوجته السابقة وأفضل مراسلة لديه قبل زواجها من رجل آخر عبر قصة صحفية مثيرة.',
  },
  {
    id: '920007',
    title: 'مهرجان روائع تشارلي شابلن',
    categoryId: 'fc_mov_comedy',
    posterUrl: 'https://archive.org/services/img/charlie_chaplin_film_fest',
    streamUrl: 'https://archive.org/download/charlie_chaplin_film_fest/charlie_chaplin_film_fest.mp4',
    year: '1938',
    rating: '8.2',
    duration: '75 د',
    durationSecs: 4500,
    genre: 'كوميديا، عائلي',
    director: 'تشارلي شابلن',
    cast: 'تشارلي شابلن، إدنا بيرفيانس',
    country: 'الولايات المتحدة',
    plot: 'باقة كلاسيكية من أجمل مغامرات الصعلوك الطريف ومواقفه الكوميدية الخالدة مع السينما الصامتة.',
  },
  {
    id: '920008',
    title: 'منزل التل المسكون (House on Haunted Hill)',
    categoryId: 'fc_mov_mystery',
    posterUrl: 'https://archive.org/services/img/house_on_haunted_hill_ipod',
    streamUrl: 'https://archive.org/download/house_on_haunted_hill_ipod/house_on_haunted_hill.mp4',
    year: '1959',
    rating: '7.6',
    duration: '75 د',
    durationSecs: 4500,
    genre: 'غموض، تشويق',
    director: 'ويليام كاسل',
    cast: 'فينسنت برايس، كارول أوهمارت',
    country: 'الولايات المتحدة',
    plot: 'مليونير غريب الأطوار يعرض مبلغاً ضخماً على خمسة غرباء إذا استطاعوا قضاء ليلة كاملة في قصر مهجور مليء بالأسرار.',
  },
  {
    id: '920009',
    title: 'السرعة والغضب الكلاسيكي (1955)',
    categoryId: 'fc_mov_action',
    posterUrl: 'https://archive.org/services/img/TheFastandtheFuriousJohnIreland1954goofyrip',
    streamUrl: 'https://archive.org/download/TheFastandtheFuriousJohnIreland1954goofyrip/TheFastandtheFuriousJohnIreland1954goofyrip.mp4',
    year: '1955',
    rating: '7.1',
    duration: '73 د',
    durationSecs: 4380,
    genre: 'أكشن، سباقات، جريمة',
    director: 'جون آيرلاند',
    cast: 'جون آيرلاند، دوروثي مالون',
    country: 'الولايات المتحدة',
    plot: 'شاب متهم زوراً يهرب بسيارة رياضية ويشارك في سباق عبر الحدود لإثبات براءته وسط مطاردات الشرطة.',
  },
  {
    id: '920010',
    title: 'عازف المزمار (The Pied Piper of Hamelin)',
    categoryId: 'fc_mov_anim',
    posterUrl: 'https://archive.org/services/img/The_Pied_Piper_of_Hamelin',
    streamUrl: 'https://archive.org/download/The_Pied_Piper_of_Hamelin/pied.mp4',
    year: '1957',
    rating: '7.4',
    duration: '87 د',
    durationSecs: 5220,
    genre: 'عائلي، مغامرة، موسيقي',
    director: 'بريتاش ويندست',
    cast: 'فان جونسون، كلود راينز',
    country: 'الولايات المتحدة',
    plot: 'الحكاية الشهيرة لمدينة هاملين وعازف المزمار الغامض الذي يخلص أهل المدينة من أزمة صعبة ثم يطالبهم بالوفاء بوعدهم.',
  },
  {
    id: '920011',
    title: 'شبح الأوبرا الكلاسيكي (1925)',
    categoryId: 'fc_mov_mystery',
    posterUrl: 'https://archive.org/services/img/the-phantom-of-the-opera-1925',
    streamUrl: 'https://archive.org/download/the-phantom-of-the-opera-1925/The%20Phantom%20of%20the%20Opera%201925.mp4',
    year: '1925',
    rating: '7.8',
    duration: '93 د',
    durationSecs: 5580,
    genre: 'غموض، دراما، كلاسيكي',
    director: 'روبرت جوليان',
    cast: 'لون تشاني، ماري فيلبين',
    country: 'الولايات المتحدة',
    plot: 'شبح غامض يختبئ في سراديب دار أوبرا باريس ويسعى لجعل المغنية الشابة كريستين نجمة العرض الأولى.',
  },
  {
    id: '920012',
    title: 'نوسفيراتو — سمفونية الغموض (1922)',
    categoryId: 'fc_mov_mystery',
    posterUrl: 'https://archive.org/services/img/nosferatu-1922',
    streamUrl: 'https://archive.org/download/nosferatu-1922/Nosferatu%20%281922%29.mp4',
    year: '1922',
    rating: '8.0',
    duration: '94 د',
    durationSecs: 5640,
    genre: 'غموض، كلاسيكي',
    director: 'إف دبليو مورناو',
    cast: 'ماكس شريك، غوستاف فون فانغنهايم',
    country: 'ألمانيا',
    plot: 'تحفة سينمائية كلاسيكية عن كاتب شاب يسافر إلى قلعة نائية لإتمام صفقة عقارية مع نبيل غريب الأطوار.',
  },
];

const SERIES_CATEGORIES: Array<{ id: string; name: string }> = [
  { id: 'fc_ser_arabic', name: 'برامج عربية وإسلامية' },
  { id: 'fc_ser_anim', name: 'مسلسلات كرتون كلاسيكية' },
  { id: 'fc_ser_classic', name: 'روائع الكوميديا الكلاسيكية' },
];

const FREE_SERIES: FreeSeries[] = [
  {
    id: '930001',
    title: 'الإتقان لتلاوة القرآن — د. أيمن سويد',
    categoryId: 'fc_ser_arabic',
    posterUrl: 'https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com',
    year: '2011',
    rating: '9.2',
    genre: 'قرآن وتجويد، تعليمي عربي',
    cast: 'الدكتور أيمن رشدي سويد',
    plot: 'سلسلة حلقات عربية تعليمية متكاملة في أحكام التجويد ومخارج الحروف وتلاوة القرآن الكريم بأسلوب مبسط وواضح.',
    episodes: [
      {
        id: '940001',
        title: 'الحلقة 1 — قواعد التلاوة ومخارج الحروف',
        streamUrl: 'https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0037-Iqraa-12-03-2011_aletqan_Aymn-Sewd.mp4',
        imageUrl: 'https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com',
        duration: '45 د',
        durationSecs: 2700,
        plot: 'درس تطبيقي في ضبط مخارج الحروف وتلاوة الآيات القرآنية.',
      },
      {
        id: '940002',
        title: 'الحلقة 2 — تطبيقات عملية في التجويد',
        streamUrl: 'https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0041-Iqraa-07-05-2011_aletqan_Aymn-Sewd.mp4',
        imageUrl: 'https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com',
        duration: '45 د',
        durationSecs: 2700,
        plot: 'تطبيق عملي مع تصحيح التلاوة وأحكام المدود.',
      },
      {
        id: '940003',
        title: 'الحلقة 3 — إتقان الحروف والصفات',
        streamUrl: 'https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0042-Iqraa-14-05-2011_aletqan_Aymn-Sewd.mp4',
        imageUrl: 'https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com',
        duration: '45 د',
        durationSecs: 2700,
        plot: 'شرح صفات الحروف وكيفية النطق السليم أثناء التلاوة.',
      },
      {
        id: '940004',
        title: 'الحلقة 4 — مختارات قرآنية مع الشرح',
        streamUrl: 'https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0041-Iqraa-07-05-2011_aletqan_Aymn-Sewd-S.mp4',
        imageUrl: 'https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com',
        duration: '30 د',
        durationSecs: 1800,
        plot: 'مراجعة لأهم قواعد التجويد في السور القرآنية.',
      },
    ],
  },
  {
    id: '930002',
    title: 'مغامرات بوباي البحار (Popeye)',
    categoryId: 'fc_ser_anim',
    posterUrl: 'https://archive.org/services/img/popeye_patriotic_popeye',
    year: '1954',
    rating: '8.5',
    genre: 'رسوم متحركة، كوميديا، أطفال',
    cast: 'جاك ميرسر، ماي كويستيل',
    plot: 'مجموعة من الحلقات الكرتونية الكلاسيكية الممتعة للبحار الشهير بوباي ومغامراته الطريفة مع السبانخ.',
    episodes: [
      {
        id: '940011',
        title: 'الحلقة 1 — بوباي الوطني',
        streamUrl: 'https://archive.org/download/popeye_patriotic_popeye/popeye_patriotic_popeye_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/popeye_patriotic_popeye',
        duration: '7 د',
        durationSecs: 420,
        plot: 'بوباي في مغامرة مشوقة ومضحكة للدفاع عن السلامة العامة.',
      },
      {
        id: '940012',
        title: 'الحلقة 2 — المحقق بوباي',
        streamUrl: 'https://archive.org/download/popeye_private_eye_popeye/popeye_private_eye_popeye_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/popeye_private_eye_popeye',
        duration: '7 د',
        durationSecs: 420,
        plot: 'بوباي يعمل محققاً خاصاً لحل لغز اختفاء غامض.',
      },
      {
        id: '940013',
        title: 'الحلقة 3 — ليلة أرق بوباي',
        streamUrl: 'https://archive.org/download/popeye_shuteye_popeye/popeye_shuteye_popeye_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/popeye_shuteye_popeye',
        duration: '7 د',
        durationSecs: 420,
        plot: 'مواقف كوميدية حين يحاول بوباي النوم وسط إزعاج غير متوقع.',
      },
      {
        id: '940014',
        title: 'الحلقة 4 — بوباي لا يخاف الأشباح',
        streamUrl: 'https://archive.org/download/popeye_i_dont_scare/popeye_i_dont_scare_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/popeye_i_dont_scare',
        duration: '7 د',
        durationSecs: 420,
        plot: 'بوباي يتحدى الخرافات في ليلة مليئة بالمفاجآت المضحكة.',
      },
      {
        id: '940015',
        title: 'الحلقة 5 — تاكسي بوباي السريع',
        streamUrl: 'https://archive.org/download/popeye_taxi-turvey/popeye_taxi-turvey_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/popeye_taxi-turvey',
        duration: '7 د',
        durationSecs: 420,
        plot: 'منافسة مجنونة بسيارات الأجرة في شوارع المدينة.',
      },
      {
        id: '940016',
        title: 'الحلقة 6 — مفاجأة الهالوين',
        streamUrl: 'https://archive.org/download/popeye_fright_to_the_finish/popeye_fright_to_the_finish_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/popeye_fright_to_the_finish',
        duration: '7 د',
        durationSecs: 420,
        plot: 'مغامرة طريفة في القصر القديم مع بوباي وأوليف.',
      },
    ],
  },
  {
    id: '930003',
    title: 'سلسلة كرتون بيتي بوب الكلاسيكية',
    categoryId: 'fc_ser_anim',
    posterUrl: 'https://archive.org/services/img/BettyBoopCartoons',
    year: '1936',
    rating: '7.9',
    genre: 'رسوم متحركة، موسيقي، كلاسيكي',
    cast: 'ماي كويستيل، استوديو فلايشر',
    plot: 'حلقات كرتونية موسيقية كلاسيكية من الثلاثينيات برسومات يدوية جميلة ومواقف طريفة مناسبة للعائلة.',
    episodes: [
      {
        id: '940021',
        title: 'الحلقة 1 — أغنية في اليوم',
        streamUrl: 'https://archive.org/download/BettyBoopCartoons/Betty_Boop_A_Song_a_Day_1936_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/BettyBoopCartoons',
        duration: '7 د',
        durationSecs: 420,
        plot: 'حكاية موسيقية مرحة في عيادة الطبيب.',
      },
      {
        id: '940022',
        title: 'الحلقة 2 — قراءة الكف الطريفة',
        streamUrl: 'https://archive.org/download/BettyBoopCartoons/Betty_Boop_Is_My_Palm_Read_1932_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/BettyBoopCartoons',
        duration: '7 د',
        durationSecs: 420,
        plot: 'مواقف كوميدية مع التوقعات والطالع في المهرجان.',
      },
      {
        id: '940023',
        title: 'الحلقة 3 — نشاط وحيوية',
        streamUrl: 'https://archive.org/download/BettyBoopCartoons/Betty_Boop_More_Pep_1936_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/BettyBoopCartoons',
        duration: '7 د',
        durationSecs: 420,
        plot: 'مغامرة مليئة بالحركة والحيوية في المطبخ.',
      },
      {
        id: '940024',
        title: 'الحلقة 4 — عطسة الموسم',
        streamUrl: 'https://archive.org/download/BettyBoopCartoons/Betty_Boops_KerCho_1932_512kb.mp4',
        imageUrl: 'https://archive.org/services/img/BettyBoopCartoons',
        duration: '7 د',
        durationSecs: 420,
        plot: 'سباق ومواقف ساخرة تنتهي بنهاية غير متوقعة.',
      },
    ],
  },
  {
    id: '930004',
    title: 'سلسلة الكوميديا الكلاسيكية الخالدة',
    categoryId: 'fc_ser_classic',
    posterUrl: 'https://archive.org/services/img/TheThreeStooges',
    year: '1934',
    rating: '8.3',
    genre: 'كوميديا، كلاسيكي',
    cast: 'مو هاوارد، لاري فاين، كيرلي، تشارلي شابلن',
    plot: 'مختارات من أشهر الحلقات الكوميدية الكلاسيكية المفتوحة في تاريخ السينما والتلفزيون.',
    episodes: [
      {
        id: '940031',
        title: 'الحلقة 1 — المهرجون الثلاثة في ورطة',
        streamUrl: 'https://archive.org/download/TheThreeStooges/TheThreeStooges-001-WomanHaters1934moeLarryCurleydivxdabaron19m20s.mp4',
        imageUrl: 'https://archive.org/services/img/TheThreeStooges',
        duration: '19 د',
        durationSecs: 1160,
        plot: 'ثلاثي الكوميديا الشهير في أولى مغامراتهم المضحكة بالقطار.',
      },
      {
        id: '940032',
        title: 'الحلقة 2 — مغامرات شابلن الكوميدية',
        streamUrl: 'https://archive.org/download/charlie_chaplin_film_fest/charlie_chaplin_film_fest.mp4',
        imageUrl: 'https://archive.org/services/img/charlie_chaplin_film_fest',
        duration: '75 د',
        durationSecs: 4500,
        plot: 'مختارات ممتعة من أفلام تشارلي شابلن القصيرة الكلاسيكية.',
      },
    ],
  },
];

// URL Registry (in-memory + localStorage persistence so playback works after page reloads)
const urlMap = new Map<string, string>();

function urlKey(kind: string, id: string): string {
  return `${kind}:${id.trim()}`;
}

function registerUrl(kind: string, id: string, streamUrl: string): void {
  if (!id || !streamUrl) return;
  urlMap.set(urlKey(kind, id), streamUrl);
}

function seedBuiltinUrls(): void {
  for (const ch of BUILTIN_CHANNELS) registerUrl('live', ch.id, ch.streamUrl);
  for (const m of FREE_MOVIES) registerUrl('movie', m.id, m.streamUrl);
  for (const s of FREE_SERIES) {
    for (const ep of s.episodes) registerUrl('series', ep.id, ep.streamUrl);
  }
}

function loadPersistedUrls(): void {
  try {
    const raw = localStorage.getItem(FREE_URLS_STORAGE_KEY);
    if (!raw) return;
    const obj = JSON.parse(raw) as Record<string, string>;
    for (const [k, v] of Object.entries(obj)) {
      if (v) urlMap.set(k, v);
    }
  } catch {
    // Ignore corrupt cache
  }
}

function persistUrls(): void {
  try {
    const obj: Record<string, string> = {};
    urlMap.forEach((v, k) => {
      obj[k] = v;
    });
    localStorage.setItem(FREE_URLS_STORAGE_KEY, JSON.stringify(obj));
  } catch {
    // Ignore storage quota errors
  }
}

seedBuiltinUrls();
loadPersistedUrls();

export function resolveFreeMediaUrl(kind: string, id: string, fallbackExtension = ''): string {
  const norm =
    kind === Kinds.LIVE || kind === 'live'
      ? 'live'
      : kind === Kinds.MOVIES || kind === 'movie'
      ? 'movie'
      : kind === Kinds.SERIES || kind === 'series'
      ? 'series'
      : kind.toLowerCase();

  const hit = urlMap.get(urlKey(norm, id));
  if (hit) return hit;

  loadPersistedUrls();
  seedBuiltinUrls();

  return (
    urlMap.get(urlKey(norm, id)) ??
    urlMap.get(urlKey('live', id)) ??
    urlMap.get(urlKey('movie', id)) ??
    urlMap.get(urlKey('series', id)) ??
    (/^https?:\/\//i.test(fallbackExtension) ? fallbackExtension : '')
  );
}

// Live Arabic M3U snapshot cache
let cachedLiveSnapshot: {
  categories: Array<{ id: string; name: string }>;
  channels: FreeChannel[];
  fetchedAt: number;
} | null = null;
let inflightLivePromise: Promise<{
  categories: Array<{ id: string; name: string }>;
  channels: FreeChannel[];
}> | null = null;

function mapArabicLiveCategory(rawGroup: string, channelName: string): [string, string] {
  const g = rawGroup.toLowerCase();
  const n = channelName.toLowerCase();
  if (
    g.includes('news') ||
    g.includes('business') ||
    n.includes('jazeera') ||
    n.includes('arabiya') ||
    n.includes('hadath') ||
    n.includes('ekhbariya') ||
    n.includes('sky news')
  ) {
    return ['fc_live_news', 'أخبار عربية واقتصاد'];
  }
  if (g.includes('sport') || n.includes('sport') || n.includes('riyadia') || n.includes('arryadia')) {
    return ['fc_live_sports', 'رياضة عربية'];
  }
  if (
    g.includes('religious') ||
    n.includes('quran') ||
    n.includes('sunnah') ||
    n.includes('makkah') ||
    n.includes('iqraa')
  ) {
    return ['fc_live_religious', 'قرآن ودينية'];
  }
  if (
    g.includes('kids') ||
    g.includes('animation') ||
    n.includes('spacetoon') ||
    n.includes('taha') ||
    n.includes('atfal')
  ) {
    return ['fc_live_kids', 'أطفال وكرتون'];
  }
  if (
    g.includes('movie') ||
    g.includes('series') ||
    g.includes('comedy') ||
    n.includes('aflam') ||
    n.includes('drama') ||
    n.includes('bab al hara') ||
    n.includes('maraya')
  ) {
    return ['fc_live_movies', 'أفلام ومسلسلات'];
  }
  if (g.includes('documentary') || g.includes('education') || g.includes('culture') || g.includes('science')) {
    return ['fc_live_doc', 'وثائقيات وثقافة'];
  }
  if (g.includes('music')) {
    return ['fc_live_music', 'موسيقى وطرب'];
  }
  return ['fc_live_general', 'قنوات عربية عامة'];
}

function parseArabicM3u(text: string): {
  categories: Array<{ id: string; name: string }>;
  channels: FreeChannel[];
} {
  const lines = text.split(/\r?\n/);
  const catMap = new Map<string, string>();
  const channels: FreeChannel[] = [];
  const seenUrls = new Set<string>();
  let nextId = 910001;

  const logoRe = /tvg-logo="([^"]*)"/;
  const groupRe = /group-title="([^"]*)"/;

  let i = 0;
  while (i < lines.length) {
    const line = lines[i].trim();
    if (line.startsWith('#EXTINF')) {
      let j = i + 1;
      while (j < lines.length && (!lines[j].trim() || lines[j].trim().startsWith('#'))) {
        j++;
      }
      const streamUrl = j < lines.length ? lines[j].trim() : '';
      const commaIdx = line.lastIndexOf(',');
      const rawTitle = commaIdx >= 0 ? line.slice(commaIdx + 1).trim() : '';
      const blocked =
        rawTitle.toLowerCase().includes('[geo-blocked]') ||
        streamUrl.includes('[') ||
        !/^https?:\/\//i.test(streamUrl);

      if (!blocked && rawTitle && !seenUrls.has(streamUrl)) {
        seenUrls.add(streamUrl);
        const cleanName = rawTitle
          .replace(/\s*\[[^\]]*]/g, '')
          .replace(/\s*\(\d{3,4}p\)/gi, '')
          .trim();
        const logo = (logoRe.exec(line)?.[1] ?? '').trim();
        const grp = groupRe.exec(line)?.[1] ?? '';
        const [catId, catName] = mapArabicLiveCategory(grp, cleanName);
        if (!catMap.has(catId)) catMap.set(catId, catName);
        const id = String(nextId++);
        registerUrl('live', id, streamUrl);
        channels.push({
          id,
          name: cleanName,
          catId,
          catName,
          streamUrl,
          logoUrl: logo,
        });
      }
      i = j + 1;
    } else {
      i++;
    }
  }

  return {
    categories: Array.from(catMap.entries()).map(([id, name]) => ({ id, name })),
    channels,
  };
}

function builtinLiveSnapshot(): {
  categories: Array<{ id: string; name: string }>;
  channels: FreeChannel[];
} {
  const catMap = new Map<string, string>();
  for (const ch of BUILTIN_CHANNELS) {
    if (!catMap.has(ch.catId)) catMap.set(ch.catId, ch.catName);
    registerUrl('live', ch.id, ch.streamUrl);
  }
  return {
    categories: Array.from(catMap.entries()).map(([id, name]) => ({ id, name })),
    channels: BUILTIN_CHANNELS,
  };
}

async function getLiveSnapshot(): Promise<{
  categories: Array<{ id: string; name: string }>;
  channels: FreeChannel[];
}> {
  if (cachedLiveSnapshot && Date.now() - cachedLiveSnapshot.fetchedAt < 5 * 60_000) {
    return cachedLiveSnapshot;
  }
  if (inflightLivePromise) return inflightLivePromise;

  inflightLivePromise = (async () => {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 10000);
      try {
        const res = await fetch('/api/free-live-m3u', { signal: controller.signal });
        if (res.ok) {
          const text = await res.text();
          if (text.includes('#EXTINF')) {
            const parsed = parseArabicM3u(text);
            if (parsed.channels.length >= 20) {
              persistUrls();
              cachedLiveSnapshot = { ...parsed, fetchedAt: Date.now() };
              return parsed;
            }
          }
        }
      } finally {
        clearTimeout(timer);
      }
    } catch {
      // Fallback to verified built-in Arabic channels
    }
    const fallback = builtinLiveSnapshot();
    persistUrls();
    cachedLiveSnapshot = { ...fallback, fetchedAt: Date.now() };
    return fallback;
  })();

  try {
    return await inflightLivePromise;
  } finally {
    inflightLivePromise = null;
  }
}

export async function freeCategories(
  kind: string,
  hiddenCats: Set<string>,
  hiddenKind: string
): Promise<CategoryItem[]> {
  let raw: Array<{ id: string; name: string }> = [];
  if (kind === Kinds.LIVE) {
    raw = (await getLiveSnapshot()).categories;
  } else if (kind === Kinds.MOVIES) {
    raw = MOVIE_CATEGORIES;
  } else if (kind === Kinds.SERIES) {
    raw = SERIES_CATEGORIES;
  }
  return raw.map((c) => {
    const k = hiddenCats.has(c.id) ? hiddenKind : kind;
    return {
      pk: `${k}::${c.id}`,
      kind: k,
      categoryId: c.id,
      categoryName: c.name,
      parentId: '',
    };
  });
}

export async function freeItems(
  kind: string,
  token: string,
  hiddenCats: Set<string>,
  hiddenIds: Set<string>,
  hiddenKind: string
): Promise<ContentItem[]> {
  if (kind === Kinds.LIVE) {
    const snap = await getLiveSnapshot();
    return snap.channels.map((ch) => {
      registerUrl('live', ch.id, ch.streamUrl);
      const rowKind = hiddenIds.has(ch.id) || hiddenCats.has(ch.catId) ? hiddenKind : Kinds.LIVE;
      return {
        pk: `${rowKind}::${ch.id}`,
        kind: rowKind,
        contentId: ch.id,
        name: ch.name,
        normalizedName: ArabicSearch.normalize(ch.name),
        category: ch.catId,
        imageUrl: ch.logoUrl ?? '',
        extension: 'm3u8',
        syncToken: token,
        rating: '',
        idNum: Number.parseInt(ch.id, 10) || 0,
      };
    });
  }

  if (kind === Kinds.MOVIES) {
    const out = FREE_MOVIES.map((m) => {
      registerUrl('movie', m.id, m.streamUrl);
      const rowKind = hiddenIds.has(m.id) || hiddenCats.has(m.categoryId) ? hiddenKind : Kinds.MOVIES;
      return {
        pk: `${rowKind}::${m.id}`,
        kind: rowKind,
        contentId: m.id,
        name: m.title,
        normalizedName: ArabicSearch.normalize(m.title),
        category: m.categoryId,
        imageUrl: m.posterUrl,
        extension: 'mp4',
        syncToken: token,
        rating: m.rating,
        idNum: Number.parseInt(m.id, 10) || 0,
      };
    });
    persistUrls();
    return out;
  }

  if (kind === Kinds.SERIES) {
    const out = FREE_SERIES.map((s) => {
      for (const ep of s.episodes) registerUrl('series', ep.id, ep.streamUrl);
      const rowKind = hiddenIds.has(s.id) || hiddenCats.has(s.categoryId) ? hiddenKind : Kinds.SERIES;
      return {
        pk: `${rowKind}::${s.id}`,
        kind: rowKind,
        contentId: s.id,
        name: s.title,
        normalizedName: ArabicSearch.normalize(s.title),
        category: s.categoryId,
        imageUrl: s.posterUrl,
        extension: 'mp4',
        syncToken: token,
        rating: s.rating,
        idNum: Number.parseInt(s.id, 10) || 0,
      };
    });
    persistUrls();
    return out;
  }

  return [];
}

export function freeDetails(
  action: 'get_vod_info' | 'get_series_info',
  id: string
): Record<string, unknown> {
  if (action === 'get_vod_info') {
    const m = FREE_MOVIES.find((x) => x.id === id);
    if (!m) return {};
    registerUrl('movie', m.id, m.streamUrl);
    return {
      info: {
        name: m.title,
        plot: m.plot,
        description: m.plot,
        releasedate: m.year,
        year: m.year,
        rating: m.rating,
        genre: m.genre,
        director: m.director,
        actors: m.cast,
        cast: m.cast,
        country: m.country,
        duration: m.duration,
        duration_secs: m.durationSecs,
        movie_image: m.posterUrl,
        cover_big: m.posterUrl,
        backdrop_path: [m.posterUrl],
        container_extension: 'mp4',
      },
      movie_data: {
        stream_id: Number.parseInt(m.id, 10) || m.id,
        name: m.title,
        container_extension: 'mp4',
      },
    };
  }

  const s = FREE_SERIES.find((x) => x.id === id);
  if (!s) return {};

  const epArray = s.episodes.map((ep, idx) => {
    registerUrl('series', ep.id, ep.streamUrl);
    return {
      id: ep.id,
      episode_num: String(idx + 1),
      title: ep.title,
      container_extension: 'mp4',
      season: 1,
      info: {
        movie_image: ep.imageUrl || s.posterUrl,
        cover_big: ep.imageUrl || s.posterUrl,
        duration: ep.duration,
        duration_secs: ep.durationSecs,
        plot: ep.plot,
      },
    };
  });

  return {
    info: {
      name: s.title,
      plot: s.plot,
      description: s.plot,
      releaseDate: s.year,
      year: s.year,
      rating: s.rating,
      genre: s.genre,
      cast: s.cast,
      cover: s.posterUrl,
      cover_big: s.posterUrl,
      backdrop_path: [s.posterUrl],
    },
    seasons: [
      {
        season_number: 1,
        name: 'الموسم 1',
        episode_count: s.episodes.length,
        cover: s.posterUrl,
      },
    ],
    episodes: {
      '1': epArray,
    },
  };
}
