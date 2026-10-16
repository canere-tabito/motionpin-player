# MotionPin — Codex最終実装プロンプト v1.1

このファイルを全文そのままCodexへ貼り付けてください。改訂設計ZIPがあれば併せて渡してください。以下はCodexへの実行指示です。

---

あなたはAndroid/Kotlin/Media3/JNI/C++/Vulkan/動画AIのリードエンジニアです。Android向け高品質ローカル動画プレーヤー「MotionPin（仮称）」を実際に実装してください。最終目標は、**利用できるCodex実行環境でAPKをビルドし、正本署名鍵で署名・検証してGitHub Releasesへ直接アップロードし、実在してダウンロードできるAPK直接URLを納品すること**です。設計提案だけで終わらず、利用可能な権限・環境の範囲で完成まで進めてください。

Workによる最終設計レビューは2026-10-16に完了しています。設計・一次資料の確認は済んでいますが、アプリ実装、モデル取得/動作、実機試験、リポジトリ作成、鍵の入手、APK/Release作成はこのレビューで実施していません。未実施を既成事実として扱わないでください。

## 1. 最初に行うことと裁量

1. 作業領域、git status/remote/branch、既存AGENTS.mdを確認して未保存変更を保護してください。添付の改訂設計ZIPがある場合はREADME、基本設計書、AGENTS、specs内5書、REVIEW_SUMMARY、REFERENCE_URLSを全文読んでください。基本設計書は本文v1.1でも互換のためファイル名が`00_基本設計書_v1.0.md`のままです。ZIPがなくてもこのプロンプトだけで実装を開始でき、些細な不足を理由に止まらないでください。
2. 接続済みGitHub認証と利用可能なrepoを確認し、同目的のMotionPinプロジェクトがあれば優先して利用してください。VeltaやPicoa/lifeosへ無断で混ぜないでください。既存がなければ`canere-tabito/motionpin-player`を候補とし、実際の所有者と作成権限を確認して作成します。既存の公開範囲を維持。不明な新規repoはprivateを安全な初期値にでき、匿名公開が必要になった時点でだけ公開範囲を確認してください。名前は仮ですが、正式配布後のapplicationId/鍵は継続します。
3. **最初のpush/PR/tag/Releaseの前に既存workflowとActions設定を読み、Actionsが動かないことを確認**してください。対象repoのActions無効化はこの全面禁止を守るための範囲で行えます。権限不足や運用上の競合があれば該当リモート操作だけ保留し、ローカル実装を続けてください。組織全体・別repoの設定は変えません。workflowを書かず、run/dispatch/rerunをせず、`[skip ci]`だけで非起動を保証しないでください。
4. Java/Android SDK/NDK/CMake/Gradle、ストレージ、ネットワーク、GitHub書込/Release権限、adb実機/エミュレータ、Vulkan GPU、正本鍵の安全な参照先を確認。秘密値を出力しないでください。既存の安定したtoolchainを活用し、必要最小限の依存だけ導入して版を固定します。
5. 短い実装計画を残し、通常再生と実AIの技術スパイクを早期に進めてください。技術的に優れた代替モデル/推論基盤/構成を自律的に採用できます。要件、画質、オフライン、配布許諾を満たし、理由と差分を短く記録することが条件です。作業軽減のための機能削除は禁止です。

軽微な技術選択は質問せず判断してください。独立した作業は続け、署名鍵・ライセンス・権限・公開範囲など不可欠なものだけ具体的な不足を報告します。不要な調査、同じ検証の繰り返し、細切れPR、毎修正のAPK生成を避けてください。

## 2. 絶対要件

- 主対象は端末内の約2〜30分の動画。ダンス・チア・サンバなど動きの激しい実写パフォーマンスの鑑賞に最適化します。
- **対応Android端末で通常4K60fps再生が必須。** H.264/HEVCの3840×2160・約60fps（59.94含む）を中心にprofile/level/codec能力を判定。元ファイルを再圧縮せずMedia3/MediaCodecで再生します。画面の物理解像度への表示スケールは通常動作です。
- **本物のAIフレーム補間を初期から本格統合。** 0.5×/0.25×の滑らかなスローを主力に、指定区間を事前生成し60fps出力を目標とします。フレーム複製・blendのみ・setPlaybackSpeedのみ・UIだけ・未接続stubをAI完成と報告しないでください。
- **画質が最優先。** 元解像度・HDR・bit深度・色・細部の保持を追求。画質維持不能なら停止/理由提示が標準です。ユーザー承認のない縮小、SDR化、8bit化、低品質モデル/圧縮への自動変更は禁止。AI生成と再encodeがあるため「完全無劣化」とは宣伝しません。
- 元動画を改変/削除/移動せず、AI生成物は別キャッシュ。動画・ピン・生成物を外部送信しない。初期コアはモデルを含め完全オフラインで完結させます。
- 時刻ピン、区間ピン、ループ、スロー速度、ズーム倍率/位置、音声設定を保存。動画横断のピン/シーンライブラリ、手動チャンネル、バックアップ/復元/再関連付けを実装します。
- 縦横両方のラグジュアリーなUI/UX。端末縦横×映像縦横の4ケースに対応し、回転で再生状態や編集内容を失いません。
- **GitHub Actionsは作成・実行・利用すべて禁止。** APKはCodexが利用できる実行環境でビルド・署名し、GitHub Releasesへ直アップロードします。

全端末の4KリアルタイムAIは約束しません。初期の正式なAI経路は品質優先の区間事前補間です。4K通常再生、4K SDR AI、HDR AIは別判定で、通常再生をAIの負荷で犠牲にしないでください。

## 3. AndroidとUI/UX

第一候補はKotlin、Coroutines/Flow、Compose、Media3 ExoPlayer、Room、DataStore、MediaStore/SAF、JNI/C++/ncnn/Vulkan。API 29以上・arm64-v8aを初期候補とし、compile/target/min SDK・AGP/Gradle/JDK/NDK/Media3は実装時の安定版互換を確認して固定してください。全責務を最初から別Gradleモジュールに分ける必要はありません。

UIは「動画」「ピン」「コレクション」の3タブ。動画に続きから/最近追加、フォルダ/検索/ソート、ピンに時刻/区間/AI済みフィルター、コレクションに元ファイルを動かさない手動チャンネルを用意します。ライブラリは段階読込・遅延サムネイルで、1本の失敗によって読み込み表示を永久継続させないでください。

色はObsidian `#0B0D12`、Graphite `#1A1D25`、本文 `#F2F3F6`、補助 `#A9AEB8`、シャンパンゴールド `#C5A771`を基準とし、余白・タイポグラフィ・操作配置まで作り込んでください。色だけの変更で完了にしません。映像が主役で、節度のあるアニメーション、48dp以上の操作領域、TalkBack、文字拡大、長押しの代替操作を備えます。

- 縦端末×横映像：幅に合わせた映像と下側操作/ピン。
- 縦端末×縦映像：映像を大きく、下部を中心に最小HUD。
- 横端末×横映像：全画面、HUD自動非表示。
- 横端末×縦映像：中央FIT、左右余白に任意の操作レール。

既定FIT。FILLは切り抜きを説明。1タップHUD、左右ダブルタップ±5秒、画面長押し中通常0.5×、ピンチズーム、拡大時の1指パン。ピン短押しは停止せず即保存、ピン長押し/メニューは前後5秒の区間候補を編集。速度は0.25/0.5/0.75/1/1.5/2×を提供し、AIの対応範囲は区別します。

ズーム中心は回転メタデータ適用後の映像正規化座標`[0,1]`で保持し、画面の余白・端末回転に依存させません。ズームをAI映像に焼き込まず、元動画/AI切替時も同じ位置を見られるようにします。区間編集は秒/ミリ秒入力・±0.1秒を提供し、フレーム精度を保証できない時は過大表示しません。

P1としてシーンプレイリスト、タグ/メモ/サムネイル強化、スマートチャンネル、精密シーク、字幕/音声track切替/PiP、同一時刻比較の高度化、実機で成立するリアルタイムAIを維持します。P0を完成させる範囲で追加し、未実装は区別してください。

## 4. 通常4K60fpsとHDR再生

SurfaceViewを優先し、Media3のCompose用PlayerSurface/ContentFrameまたは適切なinteropを使ってSurfaceの生成/サイズ/破棄を追跡してください。ズーム・クロップ・overlay・回転のためにTextureViewへ替えるならHDR・frame timing・電力の差を確認します。通常再生にBitmap変換やAI/編集パイプラインを挟みません。

サイズ/レート能力、profile/level、実際に選ばれたcodec、HW/SW、入力bitdepth/HDR、display/HDR対応を調べます。Media3が選択するdecoderを見ずにHW再生済みと仮定しません。59.94等は有理数で扱い、60Hz以上の表示能力/フレームペーシングも確認します。

HDR10/HLG/HDR10+/Dolby Visionは実際の端末/codec/profile/displayで判定してください。HDR入力の抽出成功は正常HDR表示の証明ではありません。既知のHDR非対応は再生前に説明し、アプリが無断トーンマッピングしないようにします。OS/画面の表示変換を完全制御できるという保証はしません。

Playerの寿命をCompose再compose/回転と切り離し、不要な再prepare/seekを避けます。範囲ループはclip/repeat等で範囲を正確に管理し、粗いUIタイマーだけで末尾を監視しません。gaplessやフレーム完全一致は測定なしで保証しないでください。

## 5. 実AIの統合と品質契約

### 5.1 モデルを確実に動かす

RIFE系+ncnn/Vulkanを第一候補にします。`nihui/rife-ncnn-vulkan`は推論コードの参考であり、そのデスクトップCLI全体をAndroidアプリとして使う設計にしません。NDK/CMakeでライブラリ化しJNIから呼び、ncnn Vulkanの実GPU可否を検査してください。ライブラリロード失敗/OOM/device lostをUIへ安全に返します。

まず配布根拠のあるモデル1つを固定し、Androidで2フレームから中間フレームを生成します。wrapperの対応表にはRIFE v4.6等がある一方、別世代の重みとの互換は未確認です。最新版の重みを名前だけで差し替えず、graph/blob/custom layer/任意timestep対応と変換結果を検証してください。

`MODEL_MANIFEST`に上流URL・revision、元重み/変換後重みのSHA-256、変換器/手順、license根拠、入出力値域・色順序、padding/shape、precision、対応timestepを記録します。モデルをAPKに同梱して、初回機内モードでも実AIを使える経路を目指してください。配布権が不明な重みを黙って同梱せず、確認済み代替を選ぶかその配布工程を停止します。

### 5.2 区間パイプライン

`URI → 表示PTSに基づく区間decode → scene cut判定 → native実推論 → 出力PTS → 色管理済みencode/mux → temp → 品質/属性/再生検査 → 完了cache → ピンから再生`を端末内で接続してください。

最初の5〜15秒1080p SDRで端から端まで通した後、同じ経路で4K SDRとHDR/10bitを試します。低解像度スパイクだけで最終合格にしないでください。全フレームを展開せず、固定長queue/buffer再利用/背圧制御でstream処理します。原解像度を落とす前に、メモリ再利用・推論単位・overlap tiling等を検討し、tilingは高速移動と継ぎ目を評価して採用します。

### 5.3 元画質の保持

全段に`FrameDescriptor`と`QualityContract`を設け、幅高/crop/rotation、PTS、pixel format/bitdepth、primaries/transfer/matrix/range/chroma、HDR metadata、推論精度、出力codec/profileを追跡します。互換性は`SUPPORTED / UNSUPPORTED / UNVERIFIED`で、UNKNOWNをSDR/8bitと決めつけません。

- 元映像全体を1080pへ縮小し出力だけ4Kへ戻す処理を4K保持と呼ばない。
- モデル内部のfeature pyramidやflow解像度と全画面の縮小を区別し、内部scaleと最終合成解像度を記録する。出力寸法だけで細部品質を保証しない。
- 既存RIFE CLIには8bit画像入出力の経路がある。HDRをBitmap ARGB_8888/8bit YUV/RGBへ落として属性だけ戻すことは禁止。
- HDRはP010や高精度GPU面・浮動小数点処理・Main10等のencoderを候補にするが、利用できる形式とprecisionを実際に確認する。ncnn fp16と映像10bitは別概念。FP32との差を評価してFP16を採用する。
- SDR想定モデルにPQ/HLG/linear RGBを入れただけではHDR品質を保証できない。モデル適合と色変換を分けて検証し、成立しない形式は停止・未対応表示にする。HDR保持の実装努力は初期計画に含め、TODO一行で済ませない。
- HDR10/HLGの保持と動的HDR metadataの保持は別ゲート。HDR10+/Dolby Visionのmetadata/RPUを時間の異なる生成フレームへそのままコピーしない。HDR10への変更も承認なしで行わない。
- TransformerはAI補間を自動で組み込めず、KEEP_HDRでもSDRへfallbackし得る。採用時は事前能力確認だけでなくfallback通知と実出力を監視して拒否する。
- 出力の再encodeは可逆とは限らない。codec/bitrate/profileを実際に検査し、元と同じbitrateなら同じ画質と決めつけない。可能ならencode-only対照で圧縮劣化を分離する。

品質変更が必要なら標準は停止。任意の低品質モードを提供する場合だけ、具体的な入出力差と理由を示し、その素材revision/条件に限定した明示同意を得ます。包括許諾の使い回しや自動選択はしません。

## 6. 時刻・音声・ピン・データ保全

動画は内部UUIDで管理し、content URI/MediaStore ID/名前を不変ID扱いしません。RoomにVideo、Pin、Channel/関係、AiJobを保存。Pinは元動画の整数µsでポイント時刻、または`[startUs,endUs)`、タイトル/タグ/メモ、速度、loop、zoom/pan、audioMode、AI参照を保持します。区間は`0 <= start < end <= duration`、ポイントは`0 <= t < duration`。ms APIとの変換を境界に限定してください。

出力速度`s`、原区間開始`a`、出力時刻`tOut`について`sourceTime=a+s*tOut`、出力duration=`(end-a)/s`。出力fpsは有理数として`n/fps`から毎回算出し、誤差を累積させません。VFR/Bフレームは表示PTSを基準に前後を挟み、`alpha=(sourceTime-p0)/(p1-p0)`で推論します。60fps/10秒を0.25×/60fpsへ生成すれば約40秒/2400枚です。

直前キーフレームからdecodeして必要な境界フレームを保持。重複/欠落PTS、末尾、scene cut、非中間時刻の推論を処理します。カットをまたいで補間せず原フレーム保持を使い、その重複はAI生成数へ数えません。元PTS一致箇所は元フレームを使います。

**スローを焼き込んだcacheはPlayer速度1.0で再生**してください。Pinの0.25×をもう一度かけないこと。cache↔原動画写像を、比較切替/seek/ピン記録すべてで共有します。音声は同じ区間/速度で伸長し、ピッチ保持が成立する速度のみ提供。AIの初期ミュートは許容しますが、黙って音声が壊れる処理は禁止です。

cache keyにsource fingerprint/revision、区間、速度/fps、モデル/engine hash、precision、色/encoder/audio条件を含めます。別URIや再install後の再利用は強い内容一致がある場合だけ認め、変更有無が不明なら再生成します。tempから検査・原子的renameを経て完了にし、DB/ファイル間のprocess死を起動時照合で修復。参照中cacheをLRUで消さず、cache消去後もPinを残してください。

動画権限はAPI 29–32では必要時READ_EXTERNAL_STORAGE(maxSdkVersion=32)、33ではREAD_MEDIA_VIDEO、34+では部分許可READ_MEDIA_VISUAL_USER_SELECTEDを扱います。静止画や全ファイルアクセスの不要な権限は求めません。SAFは返却grantの範囲で読取を永続取得し、復帰時にも権限を確認します。クラウドprovider/non-seekable URIはローカル・offline条件と矛盾させず説明し、無断ダウンロード/巨大コピーを避けます。

一覧はLoading/empty/partial/error、動画はavailable/permission-required/temporarily-unavailable/deleted-confirmedを分けます。部分許可の検索結果にない動画を削除扱いしません。名前・サイズ・duration・部分hashは再リンク候補抽出まで。完全hash等の強い一致がない復元は確認付きとし、同名別動画へPinを移さないでください。全動画hashを起動ごとに計算しないこと。

バックアップは版付きJSONをSAFで明示export/import。Pin/Channel/設定/動画fingerprintを保存し、元動画/AI cache/許可/鍵は含めません。一貫したDB snapshot、入力size/版/時刻検査、transaction、UUID競合の明示、再importの重複防止を実装。再installではDB/許可が失われるため再許可・再関連付けが必要で、共有動画だけ残ってもPinは自動復元しないと説明します。無断クラウド送出を防ぐbackup/data-extraction設定も確認し、OEMの挙動に依存する完全保証はしません。

## 7. RAM・GPU・熱・OS制限

GPU jobは原則1件。4K60通常再生中はAIを待機させ、次の安全なnative処理境界でbuffer/codec等を解放します。OSのスレッド優先度だけに頼らないでください。4K RGB FP32は約94.9MiB/枚、3枚だけで約284.8MiB、モデル特徴/decoder/encoder分はさらに必要です。RAM総量から対応を推定せずprobeで測ります。

UI回転でjobを二重生成しない永続状態機械を用意し、進捗/停止/失敗理由/再開を提供します。低容量/低電池/省電力/熱で画質を下げず待機または停止。Thermal APIを使いSEVERE以上では停止、回復のhysteresisを持たせ、充電中も熱を優先します。

API 35+の長時間変換は適切なmediaProcessing FGS/permission/通知とtimeout対応を実装。通常の背景実行枠は6時間/24時間であり無制限にしません。Android 16では長時間WorkManagerもjob quotaを消費し得ます。API 29–34は表示中実行と背景移行時の安全な停止/後の再開を初期経路にでき、FGSを追加するならOSの型/起動制限へ合わせてください。OS制限をサービス再起動ループや用途違いのmediaPlayback型で回避しないこと。

中断後は検査済みsegmentから再開、または区間再生成を明示。未完MP4に単純追記して完成扱いしません。native処理中の取消/timeoutの応答性とresource解放を確認します。

ncnn/JNI/全prebuilt .soについて16KB ELF alignment、APK配置、ページサイズを固定値4096と仮定しないことを確認してください。対応NDK/AGPを使い、最終APKの`zipalign -c -P 16 -v 4`とELF検査を実施。16KBロード試験とVulkan/4K60性能試験は別物です。

## 8. ライセンスと配布資産

確認済みの一般情報はwrapperがMIT、ncnn本体がBSD-3-Clause、ECCV2022-RIFE上流repoのLICENSEがMITです。**これだけで選択した全重み・派生モデル・変換物・第三者部品の配布権確定とはしません。** 選んだ正確なartifactについて入手先/版/hash/適用範囲/必要noticeを記録し、根拠不明を「配布可」と書かないでください。

アプリにライセンス画面、配布物にTHIRD_PARTY_NOTICESとモデル来歴を用意。改変表示やソース提供義務がある依存は満たします。FFmpegは必須依存にせず、必要ならビルド構成・LGPL/GPL/外部codec等の条件を個別確認します。単に「FFmpegは無料」で判断しません。私的動画をgit/Issue/Releaseへ入れず、テスト素材は権利を確認したものを用います。

## 9. 開発順と最小限の検証

1. **基盤＋実AIスパイク**：再現可能なAndroid project、Media3通常再生、nativeモデル実推論。ライセンス/Android互換の致命的問題を最初に把握。
2. **日常鑑賞を通す**：動画一覧、4向きplayer、gesture、Room Pin/区間loop、手動チャンネル、復元。4K60診断を用意。
3. **AIを動画へ接続**：区間decode→timestep→推論→encode→検査→Pinから再生。SDRを先に実証し、4K/HDRも同時期にprobeして失敗箇所を特定。
4. **品質とUXを仕上げる**：メモリ/色/熱/同意/backup/状態復旧、UIと比較。技術的に意味のある修正に検証を絞る。
5. **最終ビルド・署名・Releases直納品**：検査済みcommitと成果物を一致させる。

単体/コンパイル/lintを先行し、APKは実機/native統合に必要な節目と最終納品に限定。実機実行にAPKが必要な場合まで禁止するものではありません。モデル大量比較や効果の薄いテスト増殖は避けてください。

必須の小さな検証セット：

- PTS写像・区間境界・VFR/scene cut・二重スロー防止、Pin/zoomの回転と再起動、同名別動画の再リンク拒否、backup再import/DB migration、cache品質不一致拒否。
- 4方向UI、Pin10件以上保存・再起動、部分許可/剥奪、低容量、取消/process死、AIと再生の排他。合成1000本/100件程度の一覧で大停止がないこと。
- 適格実機でH.264/HEVCの4K60を各60秒以上。codec/profile/bitrate/端末/OS/refresh/熱、warmupとseekを除いた`dropped/(rendered+dropped)`、連続drop、AV同期を記録。暫定drop目標1%未満は保証値ではなく品質目標。測定できない項目を0にしない。
- 代表1素材で10分以上の継続再生を一度行い、熱/同期/メモリを確認。短いtestだけで30分再生全域を保証しない。全形式×全長を総当たりする必要はない。
- 本物のAIで5〜15秒の人物動作素材、1080p/4K、0.5×/0.25×、VFR/カットを小セットで兼用。出力属性、表示PTS/duration、推論回数/中間frame、処理時間/peakメモリ/熱、offline動作を確認。手足/髪/衣装/重なり/カメラ移動の少なくとも3種類を目視比較する。
- HDRは中間面のbitdepthと色経路、ramp/highlight/実映像を比較。SDR画面やスクリーンショットだけでHDR保持合格にしない。実行可能ならrelease最適化後のJNI/モデルロードと16KB環境も試す。

`VALIDATION_REPORT`にPASS/FAIL/NOT_RUN、環境、素材属性、測定方法と結果、未対応を記録。実機がなければロジック/native host/エミュレータで確認できることを進め、4K60/HDR/端末AI性能はNOT_RUNにします。実機がない理由でコード実装を途中放棄せず、検証不足を偽らないでください。

## 10. APKビルド・正本鍵・署名

- GitHub Actions/Codex外の未承認CIに委託せず、利用できるCodex実行環境でGradle release APKを作ってください。
- applicationId候補は`com.caneretabito.motionpin`。既存同IDのAPK/Releaseがあれば証明書SHA-256とversionCodeを確認し、**同じ正本アプリ署名鍵**を使います。upload key、debug key、別アプリの鍵を黙って代用しません。
- 鍵/パスワード/トークンをgit/ログ/PR/チャット/Releaseに出さない。安全なsecret経路と権限限定ファイル/環境で供給し、値をコマンド引数やshell traceへ出しません。
- 既存鍵が見つからなければ交換鍵を新規生成して公開しない。真の新規アプリで新鍵が必要なら、ユーザー管理の安全な恒久バックアップを確立・確認してから使います。一時環境にしか残らない鍵で正式公開しません。
- `./gradlew --no-daemon assembleRelease`等の実projectのtaskを使い、release JNI/model資産とABI/minSdk/version/debuggableを検査。
- Gradle signingConfigで署名済みなら重ねて別署名しません。手動ならunsigned APK→zipalign→apksigner sign→verify。署名後のAPKを加工しないでください。
- `apksigner verify --verbose --print-certs <実APK>`で検証し、証明書SHA-256を期待値と照合。APK SHA-256とサイズを記録。署名schemeはminSdkに適合させます。
- `BUILD_MANIFEST`へcommit SHA、toolchain/依存/model hash、applicationId/versionCode、APK hash/証明書指紋を残す。秘密は含めません。install/upgradeの実行環境がないなら未検証と書きます。

鍵・権限が不足しても独立した実装/検証/ビルドは完了させ、該当署名/公開工程だけ安全に停止してください。必要物を具体的に報告し、完成や公開済みを偽らないでください。

## 11. GitHub Releasesへ直接アップロード

1. Actions非起動を再確認し、レビュー/検証済みのコードを単一の妥当なbranch/PRへまとめます。既存tag/asset/履歴を無断で上書きせず、source commitを固定した一意のversion tagを用意してください。
2. `gh release create`/`gh release upload`またはGitHub RESTで署名済みAPKのバイトを直接アップロードします。Gitへの巨大APK commitやActions artifactを配布経路にしません。100MB超でも分割ZIPを既定にせず、アセット単位の実上限（確認時は2GiB未満）に従います。
3. CLIの場合は実際のrepoを明示し、既存tagを確認する`--verify-tag`、複数行notesは`--notes-file RELEASE_NOTES.md`等で扱います。未検証なら`--prerelease`と明確なタイトルを付けます。Release assetだけ同名で上書きして過去URLの内容を変えません。
4. notesに機能/未達、4K通常再生とAIの別の対応表、HDR/bitdepth制約、実測/未実機、commit、APK SHA-256、証明書指紋、必要なnoticeを添えます。
5. upload後、GitHub APIから実在アセットの`browser_download_url`、size、状態を取得。URLを推測して作りません。publicは未認証で、privateは権限のある状態で実際にGETしてredirect後もAPKであることを確認します。200でもHTMLログイン画面なら失敗です。
6. 再取得したファイルのサイズとSHA-256を署名済みローカル版と照合。APIにdigestがあれば補助的に照合できます。永続する`browser_download_url`を納品し、短命の署名URLやtoken付きURLを渡しません。
7. privateなら「GitHubログインが必要」と明記。公開URLを求めるためにrepoを勝手にpublicへ変えないこと。Android実機でタップしてDL/installしたと報告できるのは実際に行った場合だけです。

## 12. 完成の扱いと最終報告

全P0と対象形式の実機/品質ゲートが通れば正式完成版。実装/buildは進んだが実機等の検証が残る場合、鍵・許諾・権限が成立していれば、未検証を明示した**署名付きPre-release**を納品できます。これは正式合格の代わりではありません。AIがstub/未統合、必須機能が未実装、重大な品質/データ破損があるなら完成と呼ばず、可能な修正を続けてください。ライセンス/鍵/公開権限不足はPre-releaseでも免除しません。

最終報告は次を短く、実際の証拠だけで示してください。

1. repository、branch/PR、commit、APK version/applicationId。
2. 完成/未達機能。通常4K60、4K AI、HDR AIの対応範囲を分ける。
3. 実機/OS/codecと測定結果、AI推論/色/処理時間。未実機は明記。
4. 署名検証、証明書SHA-256、APK SHA-256、サイズ。
5. **GitHub ReleasesのAPK直接ダウンロードURL**、Releaseページ、認証の要否、ダウンロード後hash照合結果。
6. 残る検証/ブロッカーと次に必要なもの。秘密値を掲載しない。

## 13. 必要な一次資料（具体的な差分だけ再確認）

- Media3再生形式：https://developer.android.com/media/media3/exoplayer/supported-formats
- Surface：https://developer.android.com/media/media3/ui/surface
- Transformer HDR fallback：https://developer.android.com/media/media3/transformer/tone-mapping
- RIFE wrapper/対応モデル：https://github.com/nihui/rife-ncnn-vulkan
- RIFE上流/重みの来歴：https://github.com/hzwer/ECCV2022-RIFE
- ncnn Android build：https://github.com/Tencent/ncnn/wiki/how-to-build
- 部分動画権限：https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- 長時間worker：https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- FGS：https://developer.android.com/develop/background-work/services/fgs/service-types
- 16KB：https://developer.android.com/guide/practices/page-sizes
- 署名：https://developer.android.com/studio/publish/app-signing
- Release assets：https://docs.github.com/en/rest/releases/assets

この計画をゼロから再設計せず、まず動く通常再生と本物のAIを実装して、品質と配布まで進めてください。
