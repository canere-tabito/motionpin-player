# Validation report

## 現行：ダンサー追尾の根本修正・APK生成前検証（2026-10-16）

対象：`feat/motionpin-player`、`0.2.0-alpha1` / versionCode 2 / `com.caneretabito.motionpin`。
開始HEADは `cfc819794f9b811268398b617b63465b3caf9e29`、未コミット変更なし。
実際の本番ソースを改修・コンパイル・検証しました。**APK生成・署名・Release公開・GitHub Actions実行は行っていません。実機品質の合格報告ではありません。**

環境：Linux x86_64、Temurin JDK 21.0.12.1+1、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、API35 / Build Tools35.0.0、NDK28.1、CMake3.22.1。
独立シナリオ評価はキャッシュ済みKotlin2.0.20コンパイラで同じ本番ソースをコンパイルしました。
Android端末・エミュレータ・ハードウェアGPUは利用できず、`/dev/kvm`、`/dev/dri`、USB機器パスもありません。
adbはこの環境の書込み不可HOMEに`.android`を作ろうとして失敗しました。HOMEの変更、鍵の代用、APK生成で回避していません。

### 実装した改善と確認した根本原因

- 外観特徴の欠落・NaNを「完全一致」と扱わず拒否。予測位置・IoU・体形・元の衣装と短期平均・競合人物を用いて同衣装の誤切替を抑制。遮蔽猶予はフレーム数から元動画時刻に変更。
- 全身・上半身・脚を実際の表示比率に合わせて構成。全身中のみ一時FIT、停止中のカメラ移動を解消、速度依存の追従、余白と安全上限を分離したズーム平滑化、手動固定からの復帰を実装。
- 検査済み時刻から解析を継続し、見失ったフレームの繰返しデコードを防止。範囲外シーク・強い場面変化は再選択。旧結果の公開を世代と同期で防ぎ、ネイティブ処理を直列化。
- 実AARからMPImageが入力Bitmapを解放する仕様を確認。外観取得まで画像を保持し、選択プレビューは独立管理。RIFEへ資源を渡す際は追尾と再生の実解放を待つ。
- バッファリング・drop・低電池・省電力・熱で解析を待機。3秒リプレイによって負荷観測窓が無効になる問題も修正。再生ファイル・画質・モデルを自動で変更しない。
- 番号付き人物枠、小さい人物のタップ補助、重複枠の切替と確定操作、再選択・固定・構図変更を整理。手動操作を自動カメラが上書きする問題を解消。
- **実際のmerged Manifestで、MediaPipeの間接依存がINTERNET権限を追加する重大な方針違反を発見。** INTERNETとDataTransportのアップロード用コンポーネントを除去し、debug/release双方のマージ結果を必ず検査するGradleゲートを追加。必要な依存クラスとMedia3のACCESS_NETWORK_STATEは維持。

根拠と技術選択は [TRACKING_IMPLEMENTATION_NOTES.md](docs/TRACKING_IMPLEMENTATION_NOTES.md)。
applicationId・version・Room schema・バックアップ形式・RIFEモデル/映像パイプラインは維持しています。
RIFEの変更は追尾との資源引渡し部分に限定しました。

### 実行結果

| 検証 | 結果 | 実際に確認した範囲 |
|---|---|---|
| 依存解決とAndroid全体のKotlinコンパイル | PASS | `compileDebugKotlin`、`compileReleaseKotlin`。Room/kapt・リソース/Manifestを含む実プロジェクト |
| Androidテストのコンパイル | PASS | `compileDebugAndroidTestKotlin`。モデル/画像所有権とCompose人物選択の計4テスト。**実行はNOT_RUN** |
| JVM単体テスト | PASS | `testDebugUnitTest`：14 suite、103件、失敗/エラー/skip各0。開始版55件から48件追加。追尾・カメラ・幾何・資源制御・人物選択と既存timeline/backup/reel/quality/pipeline検査 |
| Release lint | PASS | `lintRelease`：0 errors / 28 warnings。依存更新15、KTX提案10、arm64限定1、kapt提案1、applicationContextを保持する既存singleton警告1 |
| マージ後オフライン方針 | PASS | `verifyDebugOfflineManifest` / `verifyReleaseOfflineManifest`。修正前Manifestを拒否することも確認。INTERNET・DataTransport送信用service/receiverなし |
| APK禁止タスクグラフ | PASS | 本検証の全依存グラフを事前確認。assemble要求のdry-runはガードが実行前に拒否。APK/AAB/keystore生成なし |
| 実Lite0/Lite2モデルの取得・検証 | PASS | 両方の実サイズ・SHA-256一致、float32入力/出力/metadata/ラベルを独立検査 |
| 実モデルのホスト推論 | PASS（ホスト限定） | MediaPipe1.1.0 CPUで両モデルをロード。空画像0検出、NASA公開人物画像を検出。Lite2では低スコア誤検出も観測 |
| Android AAR/API検査 | PASS（静的限定） | 1.1.0の実API、minSdk24≤29、arm64 JNI、16KB ELF LOAD alignment、Bitmap所有権。Androidロード・APK ZIP配置はNOT_RUN |
| 本番コードの旧新シナリオ比較 | PASS | 17合成シナリオと5ゲート、ラベル付きCSV再入力一致。旧コードは開始SHAから独立コンパイル。ミラー実装ではない |
| 動画フィクスチャ | PASS（生成・形式確認） | 自作の幾何学動画：H.264 / 640×360 / 30fps / 151フレーム / 5.033333秒。ffprobe確認。人物検出精度の素材ではない |
| 差分・スクリプト検査 | PASS | `git diff --check`、関連shell/Python構文。元動画・鍵・APK・workflowの追加なし |
| Android実行・実写ダンス・4K60/HDR性能 | NOT_RUN | 適格実機と注釈付き実写素材なし。端末上での描画、推論、熱、AV同期、長時間RAMは未測定 |

完全なGradle検証は最終回 `BUILD SUCCESSFUL`。最初のSDK取得は破損ZIPで失敗し再取得で解消。
編集中の初回テストはズーム揺れ回帰1件がFAIL、最終ソースを再コンパイルした全103件がPASS。
初回のmerged Manifest検査はINTERNET混入でFAIL、実装修正後のdebug/release両方がPASS。
これらの初回失敗を未実施や最初から成功したものとして扱いません。

機械可読記録：[pre-apk-build-summary.json](docs/validation/pre-apk-build-summary.json)。
詳細ログはローカルの`artifacts/validation/`、JUnit/Lintは`app/build/`。
モデルの正確なhash、形式、誤検出と再現方法は [TRACKING_HOST_MODEL_VALIDATION.md](docs/TRACKING_HOST_MODEL_VALIDATION.md)。

### 定量比較：合成入力でのロジック検証

| 指標 | 開始版 | 改修後 |
|---|---:|---:|
| 17シナリオの誤った人物への関連付け | 5 | 0 |
| 受理した人物IDの変更 | 5 | 0 |
| 同衣装交差で正しく追えた観測 | 17/20 | 19/20 |
| 2/4/8/12人候補の正しい観測（各条件） | 40/40 | 40/40 |
| 50ms刻みの5回欠落後、検出復帰後の正しい観測 | 0/7 | 7/7 |
| 急反転＋同衣装の競合 | 正解1・誤追尾3 | 正解1・曖昧3 |
| 急接近の全身クロップ違反フレーム | 30 | 0 |
| 検出揺れ時の中心加速度RMS（正規化座標/s²） | 0.747753 | 0.528774 |
| 同条件の最大ズーム変化/約30Hzサンプル | 0.002367 | 0.001947 |

人物位置とIDを生成した合成検出トレースでの値です。実写検出率・ID保証・端末フレーム落ち率を示しません。
急反転例は誤追尾を止める代わりに曖昧状態を保持します。滑らかな単独移動の中心加速度RMSは
0.041040→0.042172、急接近の最大ズーム変化は0.018054→0.037749です。
後者は人物切れを防ぐための安全制限で、全条件で滑らかさが改善したとは主張しません。

全結果・定義・ソースhash・実動画CSV手順：
[TRACKING_SCENARIO_EVALUATION.md](docs/TRACKING_SCENARIO_EVALUATION.md)、
[比較CSV](docs/validation/tracking-scenario-comparison.csv)、
[証跡JSON](docs/validation/tracking-scenario-provenance.json)。

### 処理負荷・残存リスク・引き継ぎ

解析は最大640px画像、1ネイティブセッション、6秒先読み、最大2,400アンカーに制限。
重複フレーム取得と取消時の重複稼働を抑制し、熱・電池・再生負荷に譲る実装を検証しました。
**AndroidでのCPU/GPU時間、ピークRAM、発熱、電池、4K60 OFF/ONの改善量は未測定**です。
モデル/取得器を各窓で作り直すコストやMMRの反復seekによる競合は残るため、低負荷になったという性能保証はしません。

必須の次段階：適格実機で両モデルのオフラインロード、人物選択/TalkBackと4方向SurfaceView、
同衣装/遮蔽/照明/カットの実写追尾、H.264/HEVC 4K60/HDRのOFF/Lite0/Lite2比較、
60秒と10分以上のdrop/AV同期/RAM/熱、バックグラウンド・取消・RIFE排他・replay/pin/reel・更新保持を実測。
MMRの実PTS/180度回転、似た場面のカット、長期遮蔽、全身ボックス外の手足、複数Activityの資源所有も未保証です。

[TRACKING_PRE_APK_QUALITY_GATE.md](docs/TRACKING_PRE_APK_QUALITY_GATE.md) がAPK生成段階への引き継ぎ文書です。
APK作成・最適化/R8・ZIP検査・端末テスト・正本署名・公開は、次回の明示指示とそれぞれのゲートに従います。
実測していない項目を目標値（例：drop1%未満）で埋めていません。
GitHub最終コミットと変更一覧は納品時のブランチ確認結果で示します。

---

## 以下は過去の0.1.0検証記録（今回のAPK生成・実機合格を意味しない）

確認日: 2026-10-16 UTC。対象: MotionPin 0.1.0 / versionCode 1 / `com.caneretabito.motionpin`。
**正式完成・実機合格・署名付き配布を認定するレポートではありません。**

## 環境

CodexのLinux x86_64環境、4 CPU相当、メモリ上限16 GiB。Temurin JDK 21.0.12.1+1、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、Android API 35、NDK r28b、arm64-v8a。
Android端末・エミュレータ・ハードウェアVulkan GPUは接続されていません。
ソフトウェアVulkan検査はMesa llvmpipeを使用しました。これはAndroid実機や物理GPUの代用による性能認定ではありません。

## 実行済み

| 検査 | 結果 | 根拠・範囲 |
|---|---|---|
| 固定toolchain／依存関係解決 | PASS | 公式チェックサムでGradle/JDKを照合。AndroidX/Media3/Room/Composeの固定版を解決 |
| Android arm64 JNIコンパイル | PASS | NDKでRIFE/ncnn/Warp/glslangをライブラリ化。CLIは組込みなし |
| Kotlin／Roomコンパイル | PASS | Room schema v1を出力、破壊的migrationなし |
| ロジック単体試験 | PASS（34件、失敗・skip 0） | 正確な有理数PTS、VFR/重複PTS/カット、二重スロー、区間/ズーム、hash再リンク、backup入力、AVC8/10bit、SDR値域、stale job cleanup、URI拒否、整数overflow |
| 実RIFE CPU推論 | PASS | 65×49の自己生成画像2枚、t=.25/.5、端点一致、blendとの差分、NaN拒否 |
| 実RIFE Vulkan推論 | PASS（ソフトウェア） | 同じgraph/Warp shaderで実行。CPUとの差は各RGB8値で最大1。端末GPU性能を示さない |
| モデル資産／配布根拠 | PASS（採用資産の範囲） | 正確なrepo内モデル2ファイルのSHA-256、固定commitとMITの適用根拠を記録。全RIFE重みへの一般化なし |
| APK検査器の回帰検査 | PASS | 実aapt2でbinary manifest検査、16KB/4KB ELF区別、unsignedをrelease合格にしないこと |

ネイティブ試験の再現手順・数値は [NATIVE_VALIDATION.md](docs/NATIVE_VALIDATION.md)、モデル来歴は [MODEL_MANIFEST.json](MODEL_MANIFEST.json) を参照してください。
ホストの極小画像で得た処理時間を1080p／4Kの所要時間へ外挿しません。

## 最終成果物ゲート

| 検査 | 結果 | 備考 |
|---|---|---|
| release lint / R8 / APK生成 | PASS | lint 0 errors / 23 warnings。警告は固定版より新しい依存、arm64限定、kapt/KTX提案、applicationContext singleton等。R8最適化済み未署名APKを生成 |
| APK manifest／モデルhash／全.soの16KB ELF／ZIP配置 | PASS（build検査のみ） | applicationId/version/SDK、debuggable=false、allowBackup=false、INTERNETなし、モデル2ファイルhash、arm64の全3.soのPT_LOAD ≥16KBと非圧縮ZIP配置、zipalign -c -P 16 -v 4を検査 |
| 正本署名／期待証明書SHA-256照合 | NOT_RUN | 専用正本鍵・安全なsecret参照・恒久バックアップ確認が未提供。新鍵・debug鍵の代用なし |
| GitHubへのコード送信 | NOT_RUN | repoはユーザー作成済みのpublic。Actions設定APIは403、workflowは0件。Actions無効化の確認待ち |
| Releases APK直URL／再取得hash照合 | NOT_RUN | 未署名APKを公開しない。存在しないURLは作らない |

生成物は**未署名で、そのままインストール／配布できません**。APK検査のPASSは署名・実機動作のPASSを意味しません。
最終source commit、APK SHA-256、サイズ、証明書未設定の記録はローカル納品物 `artifacts/BUILD_MANIFEST.json` に保存します。

## Android実機が必要なゲート

以下はすべて **NOT_RUN** です。測定していない値を0と扱いません。

- H.264/HEVC 3840×2160・59.94/60fps各60秒、代表1本10分の継続再生。実decoder/HW、drop率、連続drop、AV同期、熱、メモリ。
- AndroidでのJNI/model loadとVulkan実推論、5–15秒の人物動作素材1080p／4K・0.5×／0.25×、VFR/カット、手足・髪・衣装・重なり等の目視比較。
- MediaCodecの実YUV plane／stride、codec profile、encode-only対照、元解像度の細部、実出力のPTS/色、処理時間とpeakメモリ。
- 4方向UI、TalkBack／文字拡大、SurfaceView変形、回転保持、Pin10件再起動、1000動画／100ピンのUI負荷。
- 部分許可／剥奪、SAF再許可、Room実DB復元／再インポート、process死、低容量、取消、熱、FGS timeout、通常再生との排他。
- 初回機内モード動作、16KBページ端末での最適化済みnativeロード、実機インストール／更新。

[DEVICE_VALIDATION.md](docs/DEVICE_VALIDATION.md) に手順と記録欄があります。
schema v1のため過去のアプリschemaからのmigration実績はありません。単体試験でJSONを検査したことを、Room実DBの端末試験に置き換えません。

## 対応範囲と未達

| 項目 | 実装／現在の判定 |
|---|---|
| 通常4K60 | 元ファイルをMedia3/MediaCodecで再生、能力照会と実decoder診断あり。端末での持続性能はNOT_RUN |
| SDR AI | 実モデル・decode→推論→encode→属性/PTS/部分decode検査→原子的cacheのコードを接続。Android動画全経路はNOT_RUN |
| 4K SDR AI | 入力全体の縮小なし。同一経路で元サイズを要求。4K処理成立／品質／メモリはNOT_RUN |
| HDR／10bit AI | **UNSUPPORTED**。P010/Main10能力調査あり。高精度色変換／モデル適合／生成フレームのHDR metadata保持は成立していないため拒否 |
| SDRの超白／負値／RGB8 gamut外 | **UNSUPPORTED**。黙ってclipせず停止。対応には高精度の色処理が必要 |
| 不明色属性／未知形式 | UNVERIFIEDとして拒否。UNKNOWNをSDR8bitへ決めつけない |
| AI音声 | ミュートを明示。音声伸長の品質を認定していない |
| カット検出 | サンプル輝度・histogramのheuristic。誤判定のない検出ではない。保持フレームをAI推論数に数えない |
| 長時間AI再開 | 一区間を最初から再生成。未完成MP4へ追記しない |

未達をP1へ移したり、正式完成として扱ったりしていません。
P0の根拠とP1の残項目は [REQUIREMENTS_STATUS.md](docs/REQUIREMENTS_STATUS.md) に分けて記録します。
