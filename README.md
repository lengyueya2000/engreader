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
| **朗读跟读** | 逐句朗读，正在朗读的句子高亮并自动滚动，点任意句子可跳转；语速与英/美口音可调，英式下另有纪录片解说音色。语音随安装包提供，不依赖手机自带的朗读引擎 |
| **长难句拆解** | 长按句子：先给"句子主干"，再按从句/非谓语/并列分句逐段拆解，标出主语谓语，并说明为什么这样理解 |
| **理解自测** | 从文章自身生成填空题与指代题，选项同词性同语域，答案可回溯原文 |
| **学习统计** | 每日目标、连续天数、14 天阅读柱状图、阅读速度（词/分钟）与前两周对比、生词难度构成、反复查的词 |
| **离线可用** | 内置 5.9 万词条词库（15 MB）与 8 篇公版分级选段（含预生成译文），无网络也能完整使用 |

## 内容来源

- **词库**：开源项目 [ECDICT](https://github.com/skywind3000/ECDICT)（含音标、中文释义、柯林斯星级、BNC/COCA 词频、考试标签），配合其 lemma 词形还原表
- **内置文章**：[Project Gutenberg](https://www.gutenberg.org) 公版文本选段（柯南·道尔、简·奥斯汀、达尔文、玛丽·雪莱、斯托克、威尔斯），译文在构建期生成
- **外刊全文**：各媒体公开发布的 RSS 与网页（卫报、BBC、纽约时报、经济学人），仅用于个人学习阅读
- **段落译文**：Google 翻译的公开接口，失败时降级到 MyMemory；需要网络，译文保存在本机
- **朗读语音**：[Piper](https://github.com/rhasspy/piper) 的 VITS 模型，用 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 推理。英式 `en_GB-alan-medium`（[mimic3-voices](https://github.com/MycroftAI/mimic3-voices) 的 apope 录音）、美式 `en_US-lessac-medium`（LibriTTS/Blizzard 系公开录音）、纪录片解说音色来自 [VCTK](https://datashare.ed.ac.uk/handle/10283/3443) 语料的 p274 说话人（CC BY 4.0）。音素化用 espeak-ng 的英语数据

## 朗读语音

语音模型在安装包里，不读设备上的系统 TTS——同一个应用在不同手机上会得到完全不同的音色，有的手机甚至没有英语语音包，而"听文章"是这个应用的主要功能之一。

| 口音 | 音色 | 模型 | 说明 |
| --- | --- | --- | --- |
| 英式 | Alan | `en_GB-alan-medium` | 标准朗读，男声 |
| 英式 | Narrator | `en_GB-vctk-medium` p274 | 纪录片解说，男声 |
| 美式 | Lessac | `en_US-lessac-medium` | 标准朗读，女声 |

**没有开源模型是真正的大卫·爱登堡。** 公开可用的 Piper / VITS 语音都是在 VCTK、LibriVox、OpenSLR 这类公开语料上训练的，没有任何一个是拿真人解说员的录音训出来的——那是声音克隆，需要本人授权。这里能做的是从公开语料里挑出**听感最接近纪录片解说**的说话人：从 VCTK 的 109 个说话人里，先按语料自带的 `speaker-info.txt` 排除苏格兰、爱尔兰、威尔士、北爱尔兰、印度与美国口音（这些不是"英音"），剩下 15 个英格兰男声，再按可测量的朗读特征排序。下表每个数字都是 5 次合成的平均值，`±` 是标准差：

| 说话人 | 中位基频 | 语速 | 停顿次数 | 音高跨度 | 语料标注 |
| --- | --- | --- | --- | --- | --- |
| Alan（原有） | 93.3 ±0.2 | 179 ±1.2 | 11.6 ±1.0 | 1.88 ±0.07 | 单说话人 |
| **p274（选中）** | 100.0 ±0.6 | 208 ±2.9 | **6.4 ±2.2** | 3.19 ±0.16 | 22 岁，Essex |
| p287 | 99.9 ±0.5 | 239 ±5.8 | 0.2 ±0.4 | **4.02 ±0.25** | 23 岁，York |
| p226 | 112.7 ±0.6 | 219 ±2.4 | 1.6 ±1.7 | 3.32 ±0.10 | 22 岁，Surrey |
| p254 | 82.3 ±0.6 | 244 ±9.2 | 0.2 ±0.4 | 7.49 ±8.6 | 21 岁，Surrey |

选 p274 的理由是它**在从句之间真的停下来**，而且语速最接近解说（208 wpm，原有 Alan 是 179）。p287 的音高跨度更大，但语速 239 wpm、几乎不停顿，听感更接近播报；p254 的音高跨度看着最高，但 5 次之间从 3 跳到 20 半音，这个数字本身不稳定，不能作为依据。

两点必须说明，否则上面的表会被误读：

- **VITS 每次合成都带随机噪声，单次测量会骗人。** 最初一版按单次结果排序，把 p274 的停顿数读成 2；5 次平均后是 6.4。基频和音高跨度的重复性很好（±0.6 Hz、±0.16 半音），停顿数与语速的波动则和说话人之间的差距同量级，所以排序以基频和音高跨度为主，停顿与语速只作参考。
- **口音判断用的是语料自己的标注，不是声学测量。** 试过用 TRAP–BATH 元音的前两个共振峰来分辨南方英音，但在 200–400 ms 的合成词上 LPC 估计会在两次运行之间翻转符号，那等于抛硬币。苏格兰/爱尔兰/威尔士这类区分，语料标注是可靠的，声学推断不是。

测量脚本在 `tools/voice/measure.py`，换语料重新挑人时可以跑：

```bash
python tools/voice/measure.py \
  --single alan=<解包后的 en_GB-alan-medium 目录> \
  --vctk <解包后的 en_GB-vctk-medium 目录> \
  --espeak <任一语音包里的 espeak-ng-data> \
  --sheet speaker-info.txt --english-only
```

## 构建

环境要求：JDK 17+、Android SDK（platform 35、build-tools 35）、Gradle 8.9。

```bash
# 首次需要生成数据资产（已生成则可跳过）
python tools/build_dict.py          # -> app/src/main/assets/dict.db
python tools/build_seed.py          # -> app/src/main/assets/seed_articles.json
python tools/build_translations.py  # 为内置选段补中文译文（写回同一文件）

# 构建
./gradlew :app:assembleDebug      # 调试包（每个 ABI 一个）
./gradlew :app:testDebugUnitTest  # 单元测试
./gradlew :app:assembleRelease    # 发布包（需 keystore.properties）
```

语音模型与 sherpa-onnx 引擎已随仓库提供，无需额外步骤：模型在 `app/src/main/assets/tts/`，引擎是 `app/libs/sherpa-onnx.aar`（sherpa-onnx 没有 Maven 产物，因此随仓库提供）。构建按 ABI 拆包，`arm64-v8a` 给真机、`x86_64` 给模拟器；`armeabi-v7a` 与 `x86` 被排除，因为语音模型 60 MB，32 位进程放不下。

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
├── tts/            内置神经语音（模型随包 + 逐句队列 + 完成回调）
│   ├── Speech.kt       语音目录：口音分组、音色、模型路径与说话人编号
│   ├── NeuralTts.kt    sherpa-onnx 推理 + AudioTrack 分块播放与中断
│   └── Speaker.kt      后端路由：内置语音优先，系统 TTS 仅作降级
└── ui/             Compose 界面
```

分析层（`nlp/`、`dict/`、`translate/`）不依赖 Android SDK，因此可以在 JVM 单元测试里直接跑——`app/src/test/` 覆盖了句子切分、语法拆解、出题逻辑与译文解析。`tts/Speech.kt` 同样不碰 Android，语音目录的解析规则也在单测里。

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

- **朗读不依赖系统 TTS**：语音模型随安装包提供（英式两个、美式一个，约 60 MB），任何设备上音色一致，模拟器上也能出声。代价是安装包变大，且按 ABI 拆包：`arm64-v8a` 约 96 MB、`x86_64` 约 99 MB，真机装前者。sherpa-onnx 的原生库每个架构约 24 MB，语音模型约 60 MB，两者都压不下去。只有在内置模型载入失败时才会退回系统朗读引擎，此时音色由设备决定
- **没有开源语音是真正的大卫·爱登堡**：见上文"朗读语音"，所谓"纪录片解说"是按基频、语速与停顿测出来的语域，不是某位解说员的克隆
- **段落译文需要网络**：词库与内置选段是离线的，但任意外刊文章的译文要走在线翻译服务。译文按段落整篇发送、取回后存在本机，同一篇不会重复请求；没有网络时英文正文照常可读，只在顶部提示重试
- 翻译走的是公开接口（Google 优先、MyMemory 兜底），受对方限流与可用性影响；MyMemory 单次上限 500 字符，长段落会拆句发送，因此兜底路径明显更慢
- 部分外刊有付费墙或纯 JS 渲染，抓取失败时退回 RSS 摘要（短文仍可精读）
- 直播类文章（标题带 `– live`）由大量短段落拼成，篇幅可达数千词，段落之间不连贯；建议优先选普通报道
- 语法拆解是规则引擎而非统计句法分析器：用于定位主干与从句边界，歧义句仍需结合上下文判断。缩写会按展开形式还原（`We're expecting` → 主语 `We` + 谓语 `are expecting`），但 `'s` 只在代词与 `there`/`that` 之后当作 `is`，名词后的 `'s` 一律按所有格处理
- **个人生词率的分母是"要学的词"而不是全文词数**：只统计实词、排除功能词与句中首字母大写的专有名词，所以"生词 32/33（96%）"意思是 33 个值得学的词里有 32 个不认识，不是全篇 96% 的词都不认识。低于 20 个实词的短篇不出这个数字
- 阅读速度是估算：`session` 存的是文章总词数，读到一半也按全文计入，所以数值偏快；看两周之间的趋势比看绝对值有意义
- 生词本的"已掌握/答对过"只按本机记录判断，换设备或清数据后需要重新积累
- 理解题为自动生成，考察词汇与指代，不覆盖全文主旨理解
