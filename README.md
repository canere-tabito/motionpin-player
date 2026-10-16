# MotionPin

端末内の動画を、時刻・区間ピン、ループ、ズーム、AIスローで見返すAndroidアプリです。
`com.caneretabito.motionpin` / Android 10（API 29）以上 / arm64-v8a。

**開発版です。実機の品質ゲートを通過した正式完成版ではありません。** 実行した検査と未検証項目は
[VALIDATION_REPORT.md](VALIDATION_REPORT.md) に記録します。通常4K60再生、4K AI、HDR AIは別の判定です。

## 実装

- Media3 ExoPlayerとSurfaceViewで元動画を再生。FIT/FILL、速度、ピンチズーム、正規化した表示位置、区間クリップ／ループ。
- ダンサー自動追尾（試験実装）：高精度EfficientDet-Lite2／軽快Lite0のオフライン2モデルから選択。明示的な人物選択、動きと衣装の特徴による誤追尾防止、数秒単位の先読み、安定した全身追尾・上半身／脚の構図、発熱・4Kフレーム落ちに応じた解析待機。元動画不変。
- 直前3秒のワンタップ区間ループ（元動画・AIキャッシュ双方）と解除時の再生復帰。
- お気に入りシーンリール：複数動画の区間ピンを順番に再生し、並べ替え／シャッフル／リピート、設定JSONバックアップ対応。
- 「動画」「ピン」「コレクション」。MediaStoreとローカルSAF、動画の検索／並べ替え、時刻・区間ピン、手動チャンネル。
- Roomに内部UUIDと元動画のµs時刻を保存。明示JSONバックアップ、入力検査、競合検出、再インポートの重複防止、元動画の再関連付け。
- 同梱RIFE v4.6をJNI／ncnn Vulkanから実行する区間補間。0.5×／0.25×、60fps出力、音声ミュート、元サイズのままストリーム処理。
- AIの入力は明示的に確認できるBT.709／8bit SDRのみ。未知の色属性、HDR、10bit、動的HDR、RGB8へ保持できない信号は理由付きで停止。
- 生成キャッシュは速度を焼き込んで1×再生。ピン・比較切替・seekは元動画の時刻で扱います。
- GPU処理は1件。通常再生へ資源を譲り、熱・省電力・低電池・低容量では画質を下げず待機／停止。中断区間は最初から再生成します。
- API 35以上はmediaProcessingサービスと通知／timeout処理。API 29–34はアプリ表示中に処理し、背景移行時に安全な境界で停止。

元動画を移動・上書き・削除しません。アプリにINTERNET権限はありません。モデルはAPKへ同梱し、実行時ダウンロードは行いません。
OSの自動バックアップを無効化しています。OEM独自の移行動作まで完全に制御する保証はありません。

AI補間と再エンコードは完全無劣化ではありません。出力属性の一致だけで見た目の品質を保証しません。
HDR AIは成立していないため拒否します。P010／Main10能力の調査と、モデル適合・中間面・動的メタデータのゲートを分離しています。

## 追加した鑑賞機能の使い方（実機未検証）

- 再生画面の照準アイコン → 映像上の黄色い人物枠から1人選択 → 位置を先読み解析して、人物の大きさと画面に合わせて構図・ズーム中心を調整。見失う／交差する場合は再選択。処理は動画端末内で完結。
- 再生操作の「直前3秒リプレイ」を押すと直前の数秒だけを繰り返し、もう一度押すと元の再生範囲へ戻ります。
- 「ピン」タブの「シーンリール」で作成し、各区間ピンのメニューから追加。順番変更・シャッフル・ループ再生が可能です。

追尾は人物同一性の保証・被写体が隠れた際の再識別・4K60の同時性能を保証しません。全身プリセットは検出された体全体を優先してズームし、ズームで失われた画素を復元するものではありません。MediaPipe検出は縮小した参照画像に限定され、再生中の4K/HDR映像やAIキャッシュの画質を変更しません。
モデル取得方法と許諾は [TRACKING_MODEL.md](docs/TRACKING_MODEL.md)、APK作成前の品質ゲートは [TRACKING_PRE_APK_QUALITY_GATE.md](docs/TRACKING_PRE_APK_QUALITY_GATE.md) を参照。モデルはローカルAPKビルド時に署名検証ではなくSHA-256照合して取り込み、アプリ実行時は完全オフラインです。

## 使い方

1. 「動画を開く」で端末内のファイルを選択するか、動画アクセス権限を許可します。部分許可にない動画は削除扱いにしません。
2. 再生中のピン操作で停止せず時刻を保存。「区間を編集」で開始／終了を秒・ミリ秒入力し、±0.1秒で調整できます。
3. 区間ピンを0.5×または0.25×に設定してAI生成。状態と停止理由を「ピン」で確認し、完了後に元動画と切り替えます。
4. バックアップはメニューから明示的に書出し／復元。動画本体、AIキャッシュ、Androidの許可、署名鍵は含みません。

再インストール後はバックアップのインポートと動画の再許可・再関連付けが必要です。
ファイル名だけでは同一動画と判定せず、完全ハッシュがない／異なる場合は確認を求めます。

## ローカルビルド

[固定ツールチェーン](docs/TOOLCHAIN.md) を用意し、次を実行します。GitHub Actionsは使用しません。

```sh
scripts/setup-native.sh
scripts/setup-tracker-model.sh # Gradle preBuildからも実行・hash検証
scripts/validate-pre-apk.sh # 両variant・AndroidテストのKotlinコンパイル、JVMテスト、release lint。APK生成なし
python3 scripts/evaluate-tracking.py --baseline cfc819794f9b811268398b617b63465b3caf9e29
```

現在はAPK生成前の検証段階です。assemble/package・署名・公開は次回の明示的な指示まで実行しません。
改修理由は [TRACKING_IMPLEMENTATION_NOTES.md](docs/TRACKING_IMPLEMENTATION_NOTES.md)、再現手順と残ゲートは [TRACKING_PRE_APK_QUALITY_GATE.md](docs/TRACKING_PRE_APK_QUALITY_GATE.md) を参照してください。

releaseビルドにはdebug鍵へのフォールバックがありません。未署名APKはインストール／公開用ではありません。
正本鍵・独立して記録した証明書SHA-256・ユーザー管理の恒久バックアップが揃った場合だけ
`scripts/sign-release.sh` を使用します。秘密値をgitやコマンド引数へ渡さず、制限したパスワードファイルを参照します。
直接アップロードと再取得hash照合は `scripts/publish-release.py` が行います。既存のtag／assetを上書きしません。

## 根拠と検証

- [MODEL_MANIFEST.json](MODEL_MANIFEST.json)：モデル／native依存の固定revision、SHA-256、正確なモデル資産への配布根拠。
- [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)：同梱ライセンス。アプリ内にも表示します。
- [実機検証手順](docs/DEVICE_VALIDATION.md)：4K60、継続再生、AI、HDR、復元、取消、16KBの独立したゲート。
- [実装要件](docs/IMPLEMENTATION_REQUIREMENTS.md) と [レビュー](docs/REVIEW_SUMMARY.md)：対象範囲と正式完成の条件。

簡易シーンリールは実装しましたが、高度なシーンプレイリスト編集、スマートチャンネル、字幕／音声track切替、PiP、リアルタイムAIは正式対応を宣言していません。
