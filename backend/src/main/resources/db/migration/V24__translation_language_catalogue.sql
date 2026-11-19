-- ADR 0040: the translation language catalogue. Translation languages are not typing languages.
--
-- 1. supported_languages.is_typing_language. The catalogue so far listed keyboard (typing) languages only, and every
--    active row was accepted by /me/typing-languages and listed by GET /languages. Existing rows keep TRUE (nothing
--    changes for am, ti, om, en, ar or the planned expansion languages). The rows added below are translation-only
--    (FALSE): the typing-language validation, GET /languages and every keyboard surface ignore them.
--    A column on the catalogue, not a derived rule ("listed in ai_model_translation_languages"), because the two are
--    independent facts: so/sw are typing languages (PLANNED) AND translation languages, fr is only the latter, gez only
--    the former. A column also keeps the typing check a single indexed predicate that cannot drift with provider rows.
--
-- 2. The languages Google Cloud Translation - Basic (v2) documents (https://cloud.google.com/translate/docs/languages,
--    the NMT table as listed on 2026-11-19: 194 rows) that the catalogue does not have yet, as translation-only rows: codes as
--    Google accepts them (BCP 47 where Google offers both: he not iw, jv not jw, fil not tl, zh-CN/zh-TW), English
--    names as Google lists them, autonyms, ISO 15924 scripts and writing directions. The regional variants fr-FR,
--    fr-CA, pt-PT and pt-BR are left out (fr and pt serve them). support_level BETA (translation quality is BETA, D46),
--    market_group GLOBAL, sort_order 1000 (after the keyboard languages; ties sort by English name).
--
-- 3. ai_model_translation_languages: per model, the languages it translates from (as_source) and into
--    (as_destination). Seeded for openai/openai-llm, google-translate/google-nmt and the dev/test stand-ins
--    mock|wiremock-http/mock-mt-1 with every Google v2 language of the catalogue (am, ti, om, en, ar, so, sw and the
--    rows of 2). OpenAI translates any language, at varying quality: the same list, BETA like every seeded capability.
--
-- 4. One "any pair" TRANSLATE capability per model: source and destination NULL. For TRANSLATE, a NULL side means
--    "any language of this model's ai_model_translation_languages in that role" (a model without such rows matches
--    nothing); no TRANSLATE row had a NULL side before, so the existing en <-> x pair rows keep their meaning and keep
--    working. 5000 characters and auto-detection like V19/V23. N languages need N rows, not N x N pairs.
--
-- Routing is unchanged: the V19 any-language TRANSLATE policies (openai 1, google-translate 2) order fr -> am like
-- en -> am. Providers stay DISABLED; nothing here makes a call possible on its own. Idempotent.

-- --------------------------------------------------------------------------------------------- 1. typing flag
ALTER TABLE supported_languages ADD COLUMN IF NOT EXISTS is_typing_language BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN supported_languages.is_typing_language IS
    'ADR 0040: a keyboard (typing) language. FALSE = translation-only: never accepted by /me/typing-languages, '
    'never listed by GET /languages.';

-- --------------------------------------------------------------------------------------------- 2. catalogue
INSERT INTO supported_languages
    (language_code, english_name, native_name, script_code, writing_direction, market_group, support_level,
     is_launch_language, is_active, sort_order, is_typing_language, metadata)
SELECT v.code, v.english, v.native, v.script, v.direction, 'GLOBAL', 'BETA', FALSE, TRUE, 1000, FALSE,
       '{"source": "google-translate-v2"}'::JSONB
  FROM (VALUES
    ('ab',       'Abkhaz',                  'Аҧсшәа',                 'Cyrl', 'LTR'),
    ('ace',      'Acehnese',                'Bahsa Acèh',             'Latn', 'LTR'),
    ('ach',      'Acholi',                  'Lëblaŋo Acoli',          'Latn', 'LTR'),
    ('af',       'Afrikaans',               'Afrikaans',              'Latn', 'LTR'),
    ('sq',       'Albanian',                'Shqip',                  'Latn', 'LTR'),
    ('alz',      'Alur',                    'Dho-Alur',               'Latn', 'LTR'),
    ('hy',       'Armenian',                'Հայերեն',                'Armn', 'LTR'),
    ('as',       'Assamese',                'অসমীয়া',                 'Beng', 'LTR'),
    ('awa',      'Awadhi',                  'अवधी',                    'Deva', 'LTR'),
    ('ay',       'Aymara',                  'Aymar aru',              'Latn', 'LTR'),
    ('az',       'Azerbaijani',             'Azərbaycanca',           'Latn', 'LTR'),
    ('ban',      'Balinese',                'Basa Bali',              'Latn', 'LTR'),
    ('bm',       'Bambara',                 'Bamanankan',             'Latn', 'LTR'),
    ('ba',       'Bashkir',                 'Башҡортса',              'Cyrl', 'LTR'),
    ('eu',       'Basque',                  'Euskara',                'Latn', 'LTR'),
    ('btx',      'Batak Karo',              'Cakap Karo',             'Latn', 'LTR'),
    ('bts',      'Batak Simalungun',        'Hata Simalungun',        'Latn', 'LTR'),
    ('bbc',      'Batak Toba',              'Hata Batak Toba',        'Latn', 'LTR'),
    ('be',       'Belarusian',              'Беларуская',             'Cyrl', 'LTR'),
    ('bem',      'Bemba',                   'Ichibemba',              'Latn', 'LTR'),
    ('bn',       'Bengali',                 'বাংলা',                   'Beng', 'LTR'),
    ('bew',      'Betawi',                  'Basa Betawi',            'Latn', 'LTR'),
    ('bho',      'Bhojpuri',                'भोजपुरी',                  'Deva', 'LTR'),
    ('bik',      'Bikol',                   'Bikol',                  'Latn', 'LTR'),
    ('bs',       'Bosnian',                 'Bosanski',               'Latn', 'LTR'),
    ('br',       'Breton',                  'Brezhoneg',              'Latn', 'LTR'),
    ('bg',       'Bulgarian',               'Български',              'Cyrl', 'LTR'),
    ('bua',      'Buryat',                  'Буряад',                 'Cyrl', 'LTR'),
    ('yue',      'Cantonese',               '粵語',                    'Hant', 'LTR'),
    ('ca',       'Catalan',                 'Català',                 'Latn', 'LTR'),
    ('ceb',      'Cebuano',                 'Sinugboanon',            'Latn', 'LTR'),
    ('ny',       'Chichewa (Nyanja)',       'Chichewa',               'Latn', 'LTR'),
    ('zh-CN',    'Chinese (Simplified)',    '简体中文',                 'Hans', 'LTR'),
    ('zh-TW',    'Chinese (Traditional)',   '繁體中文',                 'Hant', 'LTR'),
    ('cv',       'Chuvash',                 'Чӑвашла',                'Cyrl', 'LTR'),
    ('co',       'Corsican',                'Corsu',                  'Latn', 'LTR'),
    ('crh',      'Crimean Tatar',           'Qırımtatarca',           'Latn', 'LTR'),
    ('hr',       'Croatian',                'Hrvatski',               'Latn', 'LTR'),
    ('cs',       'Czech',                   'Čeština',                'Latn', 'LTR'),
    ('da',       'Danish',                  'Dansk',                  'Latn', 'LTR'),
    ('din',      'Dinka',                   'Thuɔŋjäŋ',               'Latn', 'LTR'),
    ('dv',       'Divehi',                  'ދިވެހި',                   'Thaa', 'RTL'),
    ('doi',      'Dogri',                   'डोगरी',                   'Deva', 'LTR'),
    ('dov',      'Dombe',                   'Dombe',                  'Latn', 'LTR'),
    ('nl',       'Dutch',                   'Nederlands',             'Latn', 'LTR'),
    ('dz',       'Dzongkha',                'རྫོང་ཁ',                   'Tibt', 'LTR'),
    ('eo',       'Esperanto',               'Esperanto',              'Latn', 'LTR'),
    ('et',       'Estonian',                'Eesti',                  'Latn', 'LTR'),
    ('ee',       'Ewe',                     'Eʋegbe',                 'Latn', 'LTR'),
    ('fj',       'Fijian',                  'Na Vosa Vakaviti',       'Latn', 'LTR'),
    ('fil',      'Filipino (Tagalog)',      'Filipino',               'Latn', 'LTR'),
    ('fi',       'Finnish',                 'Suomi',                  'Latn', 'LTR'),
    ('fr',       'French',                  'Français',               'Latn', 'LTR'),
    ('fy',       'Frisian',                 'Frysk',                  'Latn', 'LTR'),
    ('ff',       'Fulfulde',                'Fulfulde',               'Latn', 'LTR'),
    ('gaa',      'Ga',                      'Gã',                     'Latn', 'LTR'),
    ('gl',       'Galician',                'Galego',                 'Latn', 'LTR'),
    ('lg',       'Ganda (Luganda)',         'Luganda',                'Latn', 'LTR'),
    ('ka',       'Georgian',                'ქართული',                'Geor', 'LTR'),
    ('de',       'German',                  'Deutsch',                'Latn', 'LTR'),
    ('el',       'Greek',                   'Ελληνικά',               'Grek', 'LTR'),
    ('gn',       'Guarani',                 'Avañeʼẽ',                'Latn', 'LTR'),
    ('gu',       'Gujarati',                'ગુજરાતી',                  'Gujr', 'LTR'),
    ('ht',       'Haitian Creole',          'Kreyòl ayisyen',         'Latn', 'LTR'),
    ('cnh',      'Hakha Chin',              'Laiholh',                'Latn', 'LTR'),
    ('ha',       'Hausa',                   'Hausa',                  'Latn', 'LTR'),
    ('haw',      'Hawaiian',                'ʻŌlelo Hawaiʻi',         'Latn', 'LTR'),
    ('he',       'Hebrew',                  'עברית',                  'Hebr', 'RTL'),
    ('hil',      'Hiligaynon',              'Hiligaynon',             'Latn', 'LTR'),
    ('hi',       'Hindi',                   'हिन्दी',                    'Deva', 'LTR'),
    ('hmn',      'Hmong',                   'Hmoob',                  'Latn', 'LTR'),
    ('hu',       'Hungarian',               'Magyar',                 'Latn', 'LTR'),
    ('hrx',      'Hunsrik',                 'Hunsrik',                'Latn', 'LTR'),
    ('is',       'Icelandic',               'Íslenska',               'Latn', 'LTR'),
    ('ig',       'Igbo',                    'Asụsụ Igbo',             'Latn', 'LTR'),
    ('ilo',      'Iloko',                   'Ilokano',                'Latn', 'LTR'),
    ('id',       'Indonesian',              'Bahasa Indonesia',       'Latn', 'LTR'),
    ('ga',       'Irish',                   'Gaeilge',                'Latn', 'LTR'),
    ('it',       'Italian',                 'Italiano',               'Latn', 'LTR'),
    ('ja',       'Japanese',                '日本語',                   'Jpan', 'LTR'),
    ('jv',       'Javanese',                'Basa Jawa',              'Latn', 'LTR'),
    ('kn',       'Kannada',                 'ಕನ್ನಡ',                    'Knda', 'LTR'),
    ('pam',      'Kapampangan',             'Kapampangan',            'Latn', 'LTR'),
    ('kk',       'Kazakh',                  'Қазақ тілі',             'Cyrl', 'LTR'),
    ('km',       'Khmer',                   'ខ្មែរ',                    'Khmr', 'LTR'),
    ('cgg',      'Kiga',                    'Rukiga',                 'Latn', 'LTR'),
    ('rw',       'Kinyarwanda',             'Ikinyarwanda',           'Latn', 'LTR'),
    ('ktu',      'Kituba',                  'Kituba',                 'Latn', 'LTR'),
    ('gom',      'Konkani',                 'कोंकणी',                   'Deva', 'LTR'),
    ('ko',       'Korean',                  '한국어',                   'Kore', 'LTR'),
    ('kri',      'Krio',                    'Krio',                   'Latn', 'LTR'),
    ('ku',       'Kurdish (Kurmanji)',      'Kurmancî',               'Latn', 'LTR'),
    ('ckb',      'Kurdish (Sorani)',        'کوردیی ناوەندی',          'Arab', 'RTL'),
    ('ky',       'Kyrgyz',                  'Кыргызча',               'Cyrl', 'LTR'),
    ('lo',       'Lao',                     'ລາວ',                     'Laoo', 'LTR'),
    ('ltg',      'Latgalian',               'Latgalīšu',              'Latn', 'LTR'),
    ('la',       'Latin',                   'Latina',                 'Latn', 'LTR'),
    ('lv',       'Latvian',                 'Latviešu',               'Latn', 'LTR'),
    ('lij',      'Ligurian',                'Ligure',                 'Latn', 'LTR'),
    ('li',       'Limburgan',               'Limburgs',               'Latn', 'LTR'),
    ('ln',       'Lingala',                 'Lingála',                'Latn', 'LTR'),
    ('lt',       'Lithuanian',              'Lietuvių',               'Latn', 'LTR'),
    ('lmo',      'Lombard',                 'Lombard',                'Latn', 'LTR'),
    ('luo',      'Luo',                     'Dholuo',                 'Latn', 'LTR'),
    ('lb',       'Luxembourgish',           'Lëtzebuergesch',         'Latn', 'LTR'),
    ('mk',       'Macedonian',              'Македонски',             'Cyrl', 'LTR'),
    ('mai',      'Maithili',                'मैथिली',                   'Deva', 'LTR'),
    ('mak',      'Makassar',                'Basa Mangkasara',        'Latn', 'LTR'),
    ('mg',       'Malagasy',                'Malagasy',               'Latn', 'LTR'),
    ('ms',       'Malay',                   'Bahasa Melayu',          'Latn', 'LTR'),
    ('ms-Arab',  'Malay (Jawi)',            'بهاس ملايو',             'Arab', 'RTL'),
    ('ml',       'Malayalam',               'മലയാളം',                 'Mlym', 'LTR'),
    ('mt',       'Maltese',                 'Malti',                  'Latn', 'LTR'),
    ('mi',       'Maori',                   'Te Reo Māori',           'Latn', 'LTR'),
    ('mr',       'Marathi',                 'मराठी',                   'Deva', 'LTR'),
    ('chm',      'Meadow Mari',             'Олык марий',             'Cyrl', 'LTR'),
    ('mni-Mtei', 'Meiteilon (Manipuri)',    'ꯃꯤꯇꯩꯂꯣꯟ',                'Mtei', 'LTR'),
    ('min',      'Minang',                  'Baso Minang',            'Latn', 'LTR'),
    ('lus',      'Mizo',                    'Mizo ṭawng',             'Latn', 'LTR'),
    ('mn',       'Mongolian',               'Монгол',                 'Cyrl', 'LTR'),
    ('my',       'Myanmar (Burmese)',       'မြန်မာ',                   'Mymr', 'LTR'),
    ('nr',       'Ndebele (South)',         'isiNdebele',             'Latn', 'LTR'),
    ('new',      'Nepalbhasa (Newari)',     'नेपाल भाषा',                'Deva', 'LTR'),
    ('ne',       'Nepali',                  'नेपाली',                   'Deva', 'LTR'),
    ('nso',      'Northern Sotho (Sepedi)', 'Sesotho sa Leboa',       'Latn', 'LTR'),
    ('no',       'Norwegian',               'Norsk',                  'Latn', 'LTR'),
    ('nus',      'Nuer',                    'Thok Naath',             'Latn', 'LTR'),
    ('oc',       'Occitan',                 'Occitan',                'Latn', 'LTR'),
    ('or',       'Odia (Oriya)',            'ଓଡ଼ିଆ',                    'Orya', 'LTR'),
    ('pag',      'Pangasinan',              'Pangasinan',             'Latn', 'LTR'),
    ('pap',      'Papiamento',              'Papiamentu',             'Latn', 'LTR'),
    ('ps',       'Pashto',                  'پښتو',                   'Arab', 'RTL'),
    ('fa',       'Persian',                 'فارسی',                  'Arab', 'RTL'),
    ('pl',       'Polish',                  'Polski',                 'Latn', 'LTR'),
    ('pt',       'Portuguese',              'Português',              'Latn', 'LTR'),
    ('pa',       'Punjabi',                 'ਪੰਜਾਬੀ',                   'Guru', 'LTR'),
    ('pa-Arab',  'Punjabi (Shahmukhi)',     'پنجابی',                 'Arab', 'RTL'),
    ('qu',       'Quechua',                 'Runa Simi',              'Latn', 'LTR'),
    ('rom',      'Romani',                  'Romani čhib',            'Latn', 'LTR'),
    ('ro',       'Romanian',                'Română',                 'Latn', 'LTR'),
    ('rn',       'Rundi',                   'Ikirundi',               'Latn', 'LTR'),
    ('ru',       'Russian',                 'Русский',                'Cyrl', 'LTR'),
    ('sm',       'Samoan',                  'Gagana Samoa',           'Latn', 'LTR'),
    ('sg',       'Sango',                   'Sängö',                  'Latn', 'LTR'),
    ('sa',       'Sanskrit',                'संस्कृतम्',                  'Deva', 'LTR'),
    ('gd',       'Scots Gaelic',            'Gàidhlig',               'Latn', 'LTR'),
    ('sr',       'Serbian',                 'Српски',                 'Cyrl', 'LTR'),
    ('st',       'Sesotho',                 'Sesotho',                'Latn', 'LTR'),
    ('crs',      'Seychellois Creole',      'Kreol Seselwa',          'Latn', 'LTR'),
    ('shn',      'Shan',                    'တႆး',                     'Mymr', 'LTR'),
    ('sn',       'Shona',                   'chiShona',               'Latn', 'LTR'),
    ('scn',      'Sicilian',                'Sicilianu',              'Latn', 'LTR'),
    ('szl',      'Silesian',                'Ślōnski',                'Latn', 'LTR'),
    ('sd',       'Sindhi',                  'سنڌي',                   'Arab', 'RTL'),
    ('si',       'Sinhala (Sinhalese)',     'සිංහල',                   'Sinh', 'LTR'),
    ('sk',       'Slovak',                  'Slovenčina',             'Latn', 'LTR'),
    ('sl',       'Slovenian',               'Slovenščina',            'Latn', 'LTR'),
    ('es',       'Spanish',                 'Español',                'Latn', 'LTR'),
    ('su',       'Sundanese',               'Basa Sunda',             'Latn', 'LTR'),
    ('ss',       'Swati',                   'siSwati',                'Latn', 'LTR'),
    ('sv',       'Swedish',                 'Svenska',                'Latn', 'LTR'),
    ('tg',       'Tajik',                   'Тоҷикӣ',                 'Cyrl', 'LTR'),
    ('ta',       'Tamil',                   'தமிழ்',                    'Taml', 'LTR'),
    ('tt',       'Tatar',                   'Татарча',                'Cyrl', 'LTR'),
    ('te',       'Telugu',                  'తెలుగు',                   'Telu', 'LTR'),
    ('tet',      'Tetum',                   'Tetun',                  'Latn', 'LTR'),
    ('th',       'Thai',                    'ไทย',                     'Thai', 'LTR'),
    ('ts',       'Tsonga',                  'Xitsonga',               'Latn', 'LTR'),
    ('tn',       'Tswana',                  'Setswana',               'Latn', 'LTR'),
    ('tr',       'Turkish',                 'Türkçe',                 'Latn', 'LTR'),
    ('tk',       'Turkmen',                 'Türkmençe',              'Latn', 'LTR'),
    ('ak',       'Twi (Akan)',              'Akan',                   'Latn', 'LTR'),
    ('uk',       'Ukrainian',               'Українська',             'Cyrl', 'LTR'),
    ('ur',       'Urdu',                    'اردو',                   'Arab', 'RTL'),
    ('ug',       'Uyghur',                  'ئۇيغۇرچە',               'Arab', 'RTL'),
    ('uz',       'Uzbek',                   'Oʻzbekcha',              'Latn', 'LTR'),
    ('vi',       'Vietnamese',              'Tiếng Việt',             'Latn', 'LTR'),
    ('cy',       'Welsh',                   'Cymraeg',                'Latn', 'LTR'),
    ('xh',       'Xhosa',                   'isiXhosa',               'Latn', 'LTR'),
    ('yi',       'Yiddish',                 'ייִדיש',                  'Hebr', 'RTL'),
    ('yo',       'Yoruba',                  'Èdè Yorùbá',             'Latn', 'LTR'),
    ('yua',      'Yucatec Maya',            'Maaya tʼaan',            'Latn', 'LTR'),
    ('zu',       'Zulu',                    'isiZulu',                'Latn', 'LTR')
  ) AS v (code, english, native, script, direction)
ON CONFLICT (language_code) DO NOTHING;

-- --------------------------------------------------------------------------------------------- 3. per-model sets
CREATE TABLE IF NOT EXISTS ai_model_translation_languages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id        UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    language_id     UUID NOT NULL REFERENCES supported_languages(id) ON DELETE CASCADE,
    as_source       BOOLEAN NOT NULL DEFAULT TRUE,
    as_destination  BOOLEAN NOT NULL DEFAULT TRUE,
    is_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_model_translation_language UNIQUE (model_id, language_id),
    CONSTRAINT ck_ai_model_translation_language_role CHECK (as_source OR as_destination)
);

COMMENT ON TABLE ai_model_translation_languages IS
    'ADR 0040: the languages a model translates from/into. A TRANSLATE capability with a NULL source or destination '
    'is limited to these rows (enabled, language active) for that side.';

CREATE INDEX IF NOT EXISTS ix_ai_model_translation_languages_model
    ON ai_model_translation_languages (model_id) WHERE is_enabled;

INSERT INTO ai_model_translation_languages (model_id, language_id, as_source, as_destination, is_enabled)
SELECT md.id, l.id, TRUE, TRUE, TRUE
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id
 CROSS JOIN supported_languages l
 WHERE ((p.provider_code = 'openai' AND md.model_code = 'openai-llm')
     OR (p.provider_code = 'google-translate' AND md.model_code = 'google-nmt')
     OR (p.provider_code IN ('mock', 'wiremock-http') AND md.model_code = 'mock-mt-1'))
   AND (l.language_code IN ('am', 'ti', 'om', 'en', 'ar', 'so', 'sw')
     OR l.metadata ->> 'source' = 'google-translate-v2')
ON CONFLICT (model_id, language_id) DO NOTHING;

-- --------------------------------------------------------------------------------------------- 4. any-pair rows
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters, metadata)
SELECT md.id, 'TRANSLATE', NULL, NULL, TRUE, TRUE, 'BETA', 5000, '{"scope": "model-translation-languages"}'::JSONB
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id
 WHERE (p.provider_code = 'openai' AND md.model_code = 'openai-llm')
    OR (p.provider_code = 'google-translate' AND md.model_code = 'google-nmt')
    OR (p.provider_code IN ('mock', 'wiremock-http') AND md.model_code = 'mock-mt-1')
ON CONFLICT DO NOTHING;
