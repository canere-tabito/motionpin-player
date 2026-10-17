# Validation report

## 再生中のSDR映像色調整（2026-10-17、ソース実装・未配布）

対象ブランチ：`feat/player-live-color-controls-20261017`。元コードは`feat/motionpin-player`。MKVカバー画像修正のドラフトPR #1とは独立し、別PRとしてレビューします。

- Media3 1.6.1の`media3-effect`を追加し、ExoPlayerの`setVideoEffects`を`prepare()`前に初期化。ランタイムでBrightness/Contrast/HslAdjustment/RgbAdjustmentを切り替える。PlayerViewはSurfaceViewのまま、TextureViewへの変更や元動画のencodeは行わない。
- パレットアイコン→透明scrimのbottom sheetで5スライダー、4プリセット、無調整表示への比較、リセット。操作は即時UI反映、GPU側は65ms debounce、設定保存は450ms debounce。既存のRoom `settings` にバージョン付き値を動画／ピンIDで別々に保存、バックアップも既存の設定配列に載る。ピン削除時にその設定も削除。
- 元のMediaFormat transferが明示SDR、profileから8bitが確認できる場合だけ調整可。HDR・10bit・色属性不明は効果を適用せず、無調整再生する。8bitの判定不明なMKVは選べない場合がある。5エフェクトのGPU負荷に対する実機性能は未確認。別処理が必要な暗部／ハイライト個別補正は未実装。原画比較はピクセル一致保証ではない。
- 4K60・HDR・AIキャッシュ・人物追尾の品質ゲートを通したとは主張しない。画質の安全確認なくHDR効果を有効化しない。source fileとRoom schema、applicationId、署名設定、versionCodeは変更していない。

| 検査 | 状態 | 根拠 |
|---|---|---|
| 純Kotlinの設定値保存・復元／上下限・動画／ピン優先順位 | PASS（限定） | Kotlin CLIで`ColorGrade.kt`と同内容のコードを独立コンパイル・実行。設定往復・境界・不正値を検査。リポジトリのJVM JUnitテストそのものではない |
| Kotlin + Android全体のdebug/releaseコンパイル、JVMテスト | NOT_RUN | 作業コンテナにAndroid SDK/Gradle Android依存物なし。別途Gradleで必須検証 |
| 4K60再生のGPU負荷、カラー比較、HDR/10bit経路・途中切替、AI cache、ズーム／人物追尾 | NOT_RUN | 端末接続なし、物理デバイス未測定 |
| APKビルド、正本鍵署名、再取得hash比較 | NOT_RUN | APK生成の前にAndroidビルド検証、ユーザー実機確認を優先。Actionsは使用しない |

次工程：`./gradlew testDebugUnitTest compileDebugKotlin compileReleaseKotlin`（Actions不使用）、SDR 8bitのH.264/HEVC MP4/MKVで再生と実際の表示、性能、HDR動画を開いた際の無変換表示、画面回転、保存と復元、原画比較・高速スライダー操作を確認。失敗を見つけた場合はこのPRを修正してから正本APKを生成する。

---


## 現行：サムネイル・人物検出開始失敗の修正（2026-10-17）

対象：`feat/motionpin-player`、`0.2.0-alpha2` / versionCode 3。
開始HEAD：`df01d51782a90e4918ae2ed51886a4a03d10d85a`、未コミット変更なし。
ユーザーから、MKV等のサムネイル不足と、全動画で人物検出開始時に
`Field platform_ for F3.B not found`になる画面の報告を受けて修正しました。
今回は**APK生成・正本署名・Drive保存が明示許可済み**です。GitHub ActionsとRelease公開は使用していません。

### 確認した原因と変更

- 旧サムネイルは`ContentResolver.loadThumbnail`の1経路のみ。providerが画像を返せないと終了し、動画自体から取得できませんでした。ローカルの読取専用descriptorから`MediaMetadataRetriever.getScaledFrameAtTime`へ切り替える経路を追加。黒い冒頭など低詳細画像では最大4時点を比較し、有効な候補を保持します。
- 2 worker、8MiBのバイト単位LRU、最大512pxの要求上限（通常320×180）、providerへのCancellationSignal、キャンセル時の一時Bitmap解放を追加。ネイティブ処理中は枠を保持し、decodeとreleaseを競合させません。providerの協調timeoutは2秒、追加取得の打切り予算は5秒で、**1回のnative呼出しを強制中断できる保証ではありません**。
- URI・revision・更新時刻・サイズ・回転等をキャッシュ識別に含め、権限や再関連付け時にComposeの旧画像を即時破棄。公開済みBitmapはLRUからの削除時にもrecycleしません。
- 旧APKのmappingで`F3.B`を`MediaPipeLoggingProto.SystemInfo`と特定。実DEXから`platform_`を含む7 instance fieldsの消失を確認。実MediaPipe 1.1.0／protobuf 4.26.1を実R8 8.9.35で縮小・実行すると同じ例外を再現し、現行規則ではbuild→serialize→parseが成功しました。
- JNIから呼ぶ`Packet.create`、`PacketListCallback.process`、`SerializedMessage`のフィールド、`MediaPipeException`の名前・コンストラクタにも欠落／変形を確認。必要なJNI境界とprotobuf fieldsを保持し、R8最適化は継続。全framework保持や包括的dontwarnは使用していません。
- `assembleRelease`に実DEX／元AAR／mappingの契約検査を接続。具体的なcallback実装も検査します。エンジン読込時の`LinkageError`は既存の通知・解放経路へ渡します。

applicationId、Room schema、正本署名鍵、元動画、通常再生、RIFE、追尾モデルは維持しています。
MKVはコンテナであり、端末の抽出器／コーデックが対応しない動画のサムネイル取得を保証しません。

### 実行結果

| 検査 | 結果 | 根拠・範囲 |
|---|---|---|
| Debug／Release Kotlin、Androidテストコンパイル | PASS | 実Android全体。追加のMKV／MP4実デコードテスト2件を含む |
| JVM単体テスト | PASS | 16 suite、119件、failure/error/skip各0。サムネイルのfallback、黒い冒頭、キャンセル解放、境界・キャッシュ識別16件を追加 |
| 実R8／protobufホスト実行 | PASS | 旧規則は`platform_`欠落でexit 1、修正規則は45 bytesのserialize/parse成功。Android JNI実行の代替ではない |
| DEX検査器の自己テスト | PASS | 12件。定義・型・整合性・継承callbackなど。旧配布APKを実際にFAIL判定 |
| Release lint | PASS | 0 errors / 31 warnings。依存更新15、KTX提案13、既存singleton1、arm64限定1、kapt提案1 |
| Releaseビルドと自動runtime契約ゲート | PASS | 元AARの154 messages中、保持55 classes／293 instance fields、JNI契約5件と実callback1件。署名後APKも再検査してPASS |
| 正本署名・APK構造・モデル | PASS | 既存alpha1と証明書一致。署名／オフラインmanifest／全native 16KB ELF・ZIP配置／RIFE等26検査、Lite0/Lite2サイズ・hash・非圧縮もPASS |
| 自作MP4／MKV検証素材 | PASS（ホストのみ） | 各4秒・320×180・H.264/12fps。先頭黒画像variance 0、1秒地点variance 4418.877。元素材hash一致。生成手順付き、Release APKへは含めない |
| 実機サムネイル・最適化済みAPKで人物検出 | NOT_RUN | Android端末／エミュレータなし。実機での両モデル・MKV/MP4・更新インストールは確認が必要 |

初回Releaseビルドではframework全体を保持したため、AARにない未使用のGraphTemplate／GraphProfiler等の参照でR8がFAIL。
実JNI境界に保持規則を絞り、警告抑制を追加せず、再実行は`BUILD SUCCESSFUL`でした。
前回のKotlinコンパイル・debug単体テスト・モデルhashだけでは、このRelease起動障害を検出できなかったことを記録します。

再現コマンド：

```sh
scripts/build-local.sh compileDebugKotlin compileReleaseKotlin compileDebugAndroidTestKotlin testDebugUnitTest lintRelease assembleRelease
python3 scripts/test-mediapipe-r8.py
python3 scripts/verify-protobuf-apk.py --self-test
```

機械可読記録：[alpha2-fix-summary.json](docs/validation/alpha2-fix-summary.json)。
詳細ログは`artifacts/alpha2/`と`artifacts/release-regression/`。
署名済みAPKは71,152,769 bytes、SHA-256：
`d6fae46243d27a5cc8d2def274ed7e8488ad463da876d063ef994c87b2d1ae58`。
証明書SHA-256：`20446ba202bd4c0d2c665aa6b11f7f5c0127bb73ee38f27d64c10fabecfe0337`。
[Drive納品APK](https://drive.google.com/file/d/1Oq_XBXq9RuNokJzT6uwJgjHRPCQOCfkQ/view?usp=drivesdk)：保存後に実ファイルを再取得し、サイズとSHA-256の一致を確認済み（PASS）。既存の共有設定は変更していません。

実機では、まずalpha1から更新→問題のMKVを一覧表示→同じ動画の同じ時刻で人物選択→Lite2/Lite0切替を確認してください。
4K60/HDR、長時間の熱・メモリ、全MKV codec、nativeの強制中断、実写追尾精度は今回も未測定です。
サムネイルのBitmapは一覧用だけであり、元動画の再圧縮・SDR化・フレームレート変更は行いません。

---

## 過去：ダンサー追尾の根本修正・APK生成前検証（2026-10-16）

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
