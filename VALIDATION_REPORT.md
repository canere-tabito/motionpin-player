# Validation report

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
