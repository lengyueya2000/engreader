# 阅见英语 · EngReader

一款面向**成人泛读提升**的 Android 英语阅读应用。抓取外刊全文后逐句精读，配合离线词库、朗读跟读、长难句拆解、理解自测与记忆曲线复习。

## 功能

| 模块 | 说明 |
| --- | --- |
| **划词查词** | 点单词弹出释义卡片：音标、词性分组的释义、考频分级、发音。点按偏移量由文本布局反查，点到标点不会误判 |
| **段落中文翻译** | 每段英文下方附中文译文，可随时开关。首次打开某篇需联网获取，之后存在本机；内置选段的译文随包提供，离线也能看 |
| **多义消歧** | `found` 既是"建立"又是 `find` 的过去式，`left` 既是"左边的"又是 `leave`。按语料词频排序，主释义之外提供"另一种理解"一键切换 |
| **生词本** | 一键收藏，正文中已收藏的词带下划线高亮；支持搜索、按待复习/常错/已掌握筛选；每个词可写笔记，复习时一并显示 |
| **同族词** | 查词卡列出该词的其它形式（过去式、复数、派生词），点一下直接跳查；用词干回查词库补全 `exchange` 列没有收录的派生词 |
| **英英渐进** | 已经答对过的词，查词时先给英文释义，中文折叠在"看中文释义"后面；没答对过的词仍直接给中文 |
| **语境复习卡** | 查词时记下当时的原句，复习时先把该词挖空成"这句话里这个词是什么意思"，再对答案；答对后卡片附原文与笔记 |
| **个人生词率** | 按你的生词本算每篇"生词 N/M"与"轻松/合适/有挑战/偏难"判断，首页列表带 `生词 N 个` 角标；偏难时可先"预习生词"再读 |
| **记忆曲线复习** | Leitner 盒子（10 分钟 → 1 天 → 3 天 → 7 天 → 21 天），答对升盒、答错回退并当轮重现；卡片附同词性易混释义 |
| **朗读跟读** | 系统 TTS 逐句朗读，正在朗读的句子高亮并自动滚动，点任意句子可跳转；语速与英/美/澳口音可调 |
| **长难句拆解** | 长按句子：先给"句子主干"，再按从句/非谓语/并列分句逐段拆解，标出主语谓语，并说明为什么这样理解 |
| **理解自测** | 从文章自身生成填空题与指代题，选项同词性同语域，答案可回溯原文 |
| **学习统计** | 每日目标、连续天数、14 天阅读柱状图、阅读速度（词/分钟）与前两周对比、生词难度构成、反复查的词 |
| **离线可用** | 内置 5.9 万词条词库（15 MB）与 8 篇公版分级选段（含预生成译文），无网络也能完整使用 |

## 内容来源

- **词库**：开源项目 [ECDICT](https://github.com/skywind3000/ECDICT)（含音标、中文释义、柯林斯星级、BNC/COCA 词频、考试标签），配合其 lemma 词形还原表
- **内置文章**：[Project Gutenberg](https://www.gutenberg.org) 公版文本选段（柯南·道尔、简·奥斯汀、达尔文、玛丽·雪莱、斯托克、威尔斯），译文在构建期生成
- **外刊全文**：各媒体公开发布的 RSS 与网页（卫报、BBC、纽约时报、经济学人），仅用于个人学习阅读
- **段落译文**：Google 翻译的公开接口，失败时降级到 MyMemory；需要网络，译文保存在本机

## 构建

环境要求：JDK 17+、Android SDK（platform 35、build-tools 35）、Gradle 8.9。

```bash
# 首次需要生成数据资产（已生成则可跳过）
python tools/build_dict.py          # -> app/src/main/assets/dict.db
python tools/build_seed.py          # -> app/src/main/assets/seed_articles.json
python tools/build_translations.py  # 为内置选段补中文译文（写回同一文件）

# 构建
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:testDebugUnitTest  # 单元测试
./gradlew :app:assembleRelease    # 发布包（需 keystore.properties）
```

`tools/build_dict.py` 与 `tools/build_seed.py` 需要把原始数据放在 `tools/raw/`（该目录已 git-ignore）：
`ecdict.csv`、`lemma.en.txt` 来自 ECDICT 仓库，`pg*.txt` 来自 Project Gutenberg。
`build_translations.py` 只处理内置选段（8 篇、约 70 段），需要网络；外刊译文由 App 在运行时按需获取。

### 发布签名

`keystore.properties` 与 `*.jks` 不入库。本地生成：

```bash
keytool -genkeypair -v -keystore engreader-release.jks -alias engreader \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=EngReader, O=EngReader, C=CN"
```

然后写 `keystore.properties`：

```properties
storeFile=engreader-release.jks
storePassword=...
keyAlias=engreader
keyPassword=...
```

缺这个文件时 release 包会构建为未签名版本，而不是让构建失败。

## 代码结构

```
app/src/main/java/com/engreader/app/
├── data/           SQLite 存储与仓储（文章、生词本、进度、设置）
│   ├── UserDb              用户数据表结构
│   ├── ArticleRepository   抓取→存储→列表
│   ├── WordbookRepository  生词本与 Leitner 调度
│   └── ProgressRepository  跨表统计汇总
├── dict/           离线词库
│   ├── Dictionary          只读查询 + 词形还原 + 多义排序 + 词族检索
│   ├── WordEntry           词条、CEFR 近似分级、英文释义行
│   ├── WordFamily          解析 `exchange` 列的屈折形式，按词干补全派生词
│   └── PartOfSpeechParser  词性标记解析、后缀启发式与缩写展开表
├── nlp/            文本分析（纯 JVM，可单测）
│   ├── Sentences           句子切分（处理缩写、小数、引号）
│   ├── Paragraphs          段落与词元偏移
│   ├── GrammarAnalyzer     分句切分、主干提取、成分标注
│   ├── QuizBuilder         填空题与指代题生成
│   ├── Cloze               挖空（吸收尾随标点），复习卡与自测共用
│   ├── VocabularyProfile   个人生词率：内容词提取、判定与生词列表
│   └── VocabularyGrader    按高频词占比给文章定级
├── source/         RSS 与网页
│   ├── RssParser           拉模式 RSS/Atom 解析
│   ├── ArticleExtractor    站点无关的正文提取
│   ├── ArticleFetcher      网络请求与降级
│   └── SeedLibrary         内置选段（含预生成译文）
├── translate/      段落中文翻译
│   ├── ParagraphTranslator 引擎降级链、增量复用已缓存译文
│   ├── TranslateEngine     Google（批量）与 MyMemory（限长）两个实现
│   └── MiniJson            纯 JVM JSON 解析，供响应解析与单测
├── tts/            系统 TTS 封装（逐句队列 + 完成回调）
└── ui/             Compose 界面
```

分析层（`nlp/`、`dict/`、`translate/`）不依赖 Android SDK，因此可以在 JVM 单元测试里直接跑——`app/src/test/` 覆盖了句子切分、语法拆解、出题逻辑与译文解析。

## 数据库

用户数据在本机 SQLite（`engreader.db`），当前 schema 版本 **2**。

| 表 | 内容 |
| --- | --- |
| `article` | 抓取与内置的文章；`vocabProfile` 缓存该篇的实词表（换行分隔），换设备重算即可 |
| `word` | 生词本：Leitner 盒号、到期时间、正确/错误次数、个人笔记 |
| `lookup` | 每次查词；`sentence`/`surface` 记下当时的原句与词形，供复习卡还原语境 |
| `session` | 每次退出阅读器写入一条，用于时长、连续天数与阅读速度 |
| `quiz` | 自测成绩 |

升级走 `onUpgrade` 里的 `ALTER TABLE`，不清表：v1 → v2 只是给 `lookup` 加 `sentence`/`surface`、给 `article` 加 `vocabProfile` 并建 `idx_lookup_lemma`。每条语句前都查一次 `PRAGMA table_info`，所以中途失败后重跑是安全的。早期版本直接 drop 重建，在只有测试数据时无所谓，但会连生词本和阅读历史一起清掉。

## 设计

- **配色**：暖纸色底（`#FAF8F3`）+ 松绿主色（`#1F5A4A`）+ 琥珀强调，正文衬线、界面无衬线
- **阅读底色**独立于应用主题：纸白 / 米黄 / 夜间三选一，顶栏与操作按钮跟随阅读底色
- **触控目标**≥ 48dp，底部导航 64dp，所有可点区域有按压反馈
- 夜间模式下正文对比度 ≥ 4.5:1

## 已知限制

- 朗读依赖系统 TTS 引擎；模拟器通常未预装语音包，真机需在系统设置 → 无障碍 → 文字转语音中安装英语语音
- **段落译文需要网络**：词库与内置选段是离线的，但任意外刊文章的译文要走在线翻译服务。译文按段落整篇发送、取回后存在本机，同一篇不会重复请求；没有网络时英文正文照常可读，只在顶部提示重试
- 翻译走的是公开接口（Google 优先、MyMemory 兜底），受对方限流与可用性影响；MyMemory 单次上限 500 字符，长段落会拆句发送，因此兜底路径明显更慢
- 部分外刊有付费墙或纯 JS 渲染，抓取失败时退回 RSS 摘要（短文仍可精读）
- 直播类文章（标题带 `– live`）由大量短段落拼成，篇幅可达数千词，段落之间不连贯；建议优先选普通报道
- 语法拆解是规则引擎而非统计句法分析器：用于定位主干与从句边界，歧义句仍需结合上下文判断。缩写会按展开形式还原（`We're expecting` → 主语 `We` + 谓语 `are expecting`），但 `'s` 只在代词与 `there`/`that` 之后当作 `is`，名词后的 `'s` 一律按所有格处理
- **个人生词率的分母是"要学的词"而不是全文词数**：只统计实词、排除功能词与句中首字母大写的专有名词，所以"生词 32/33（96%）"意思是 33 个值得学的词里有 32 个不认识，不是全篇 96% 的词都不认识。低于 20 个实词的短篇不出这个数字
- 阅读速度是估算：`session` 存的是文章总词数，读到一半也按全文计入，所以数值偏快；看两周之间的趋势比看绝对值有意义
- 生词本的"已掌握/答对过"只按本机记录判断，换设备或清数据后需要重新积累
- 理解题为自动生成，考察词汇与指代，不覆盖全文主旨理解
