# MotionPin 最終技術レビュー v1.1

確認日：2026-10-16 JST。基準：添付`MotionPin_Design_v1.0.zip`内の11文書を全文確認。文書レビューのみで、APKビルド・アプリ実装・モデル実行・実機検証・GitHub変更は実施していない。

## 結論

**Codexで実装を開始できる状態。設計全体の作り直しは不要。** Media3による通常再生と、独立したJNI/AI事前補間経路の分離は適切。元解像度・HDR・bit深度の保持を要求し、維持不能時に黙って落とさない方針も維持した。

最大の未確定事項は**Android実機での4K/HDR AI経路と、採用する正確なモデル重みの再配布根拠**。汎用RIFEの名前やVulkan対応だけでは解決しない。これは実装前の文書レビューだけでは合格判定できないため、短い実推論・動画変換で確認する具体的ゲートを追加した。

本レビューの成果は設計v1.1と自己完結した`CODEX_FINAL_PROMPT.md`。P0をP1へ落とす、AIを将来TODOにする、画質を自動低下させる変更は行っていない。

## 重要な判断と修正

| 論点 | 確認した事実 / 技術判断 | 設計への反映 |
|---|---|---|
| 4K60通常再生 | Media3の形式対応と端末のcodec/display能力は別。HDR profileによりSW decoderが選ばれる場合もある。[R01,R03] | 実decoder名/HW・profile・frame drop・AV同期・熱を記録。能力照会だけで合格にしない |
| 表示面 | 公式は通常動画でSurfaceViewを優先。HDR・タイミング・電力の利点がある。[R02] | Compose連携、zoom/overlay/回転を検証して維持。TextureViewへの変更は品質比較が条件 |
| HDR AI | Transformer KEEP_HDRにもfallbackがある。RIFE CLIの画像境界にはunsigned char/stbiの8bit経路がある。[R04,R07] | FrameDescriptor/QualityContract、事前判定＋実行時fallback拒否＋出力検査。既存CLIそのままではHDR保持不可と判断 |
| RIFE/ncnn | Android ncnn buildは公式に用意される。wrapper表はv4.6等で、別世代重みの互換を示さない。[R06,R09] | JNI library統合、モデル/blob/custom layer/timestepの固定。Androidで実推論から動画へ接続 |
| 色の正当性 | P010は10bit形式だが、API定義は全codecでの利用保証ではない。[R05] | SDR/10bit/HDR10/HLG/動的HDRを分離。fp16演算と映像bitdepthを区別。HDRモデル適合は未検証 |
| 時刻 | 元設計では写像の保存のみで、再生速度の二重適用を防ぐ規則がない | 原PTS/µsと有理数出力時刻を規定。速度焼込みcacheは1×再生。VFR/カット/末尾を定義 |
| 長時間AI | API35のmediaProcessing FGSに時間枠、Android16の長時間workerにjob quotaの制約。[R14–R16] | 永続キュー、通知/timeout/境界停止、背景制約を守る再開。通常再生優先 |
| native端末互換 | 16KBページサイズはnative依存にも影響。[R18] | NDK/ELF/APK配置・全.so・ロードの確認を追加 |
| ピン生存性 | 部分許可とSAFの永続許可は万能ではなく、移動/削除で失効し得る。[R11,R12] | 権限喪失と欠落を分離、強い指紋で再リンク、版付きexport/importと再許可 |
| オフライン | OSのAuto BackupはアプリDB等も対象になり得る。[R13] | 明示exportを基本に、自動クラウド送出を抑止する設定、OEM移行の限界を記載 |
| 署名・配布 | アプリ署名鍵の継続と、整列→署名の順序が重要。GitHub assetsへの直接uploadが可能。[R19–R21] | 期待証明書照合、恒久鍵保管、実asset URL取得、再DL hash照合 |
| Actions禁止 | 既存workflowはpush/PR/releaseでも起動し得る。[R22] | 新規workflowを作らないだけでなく、リモート操作前に非起動を確認 |

一次資料の事実と、本プロジェクトへの設計判断を区別した。たとえば「動的HDRを未検証なら止める」「AI中は4K再生に譲る」「品質変更同意をジョブ単位にする」は本レビューの設計判断であり、上流機能の保証ではない。

## モデル・ライセンスの現在地

| 対象 | 確認済み | Codexに残す作業 |
|---|---|---|
| nihui/rife-ncnn-vulkan | repo LICENSEはMIT。[R06] | 採用commit、モデル資産、含む依存/改変/noticeを固定 |
| Tencent/ncnn | 本体BSD-3-Clause、同梱部品は別条件がある。[R09] | 採用buildに実際に含まれる部品を棚卸し、notice同梱 |
| hzwer/ECCV2022-RIFE | repo LICENSEはMIT。重みはREADMEから別配布先も案内。[R10] | 採用する正確な重みと派生/変換物への許諾の適用根拠を保存 |
| FFmpeg等 | FFmpegの条件はbuild構成/依存で変わる。[R23] | 本当に必要な場合だけ採用し、該当条件を満たす |

**重みをダウンロードして中身/付属条件を確認する作業は未実施。全RIFE重みを配布可とも配布不可とも断定していない。** LICENSEファイルが別にないことだけで自動的に不可とせず、repo許諾が当該artifactへ及ぶ根拠を確認する。根拠が不明な重みは公開せず、適法な代替があれば採用する。

## 残るリスクと最短の解消方法

| 優先 | 未確定事項 | Codexが行う最小確認 | 未達時 |
|---|---|---|---|
| 最優先 | 実モデルのAndroid統合/配布根拠 | モデル1つをpinし、Androidで2枚→中間1枚、次に5〜15秒SDR | 代替を具体的理由で検討。stubを完成にしない |
| 最優先 | 4K/HDR信号経路 | 同一pipelineの4K SDR、HDR rampと短い実映像、各段のpixel format | 無断縮小/8bit化を拒否し対象形式を未対応/未検証表示 |
| 高 | 4K60の持続性能 | H.264/HEVC各60秒＋代表1本10分、decoder/drop/熱/同期 | 該当形式/端末の結果だけ報告。全端末へ外挿しない |
| 高 | GPU/native RAMと熱 | fixed queueのpeakとthermal、再生開始時のAI停止 | 資源解放/待機/安全な再生成。画質を自動変更しない |
| 高 | ピン誤リンク・再install | 同名別動画/部分許可/backup再import/再許可 | 自動確定せず確認。Pinは保持 |
| 配布前必須 | 正本鍵・GitHub権限・公開範囲 | 同一applicationId証明書、恒久保管、Actions非起動、API権限 | 該当署名/公開のみ停止し不足を具体的に報告 |

HDRで高精度bufferを持てても、SDR想定モデルがHDRの見た目を維持するとは限らない。光学flow用proxyを使う等の代替も研究候補であり、このレビューでは成功を認定していない。端末・GPU・RAMの実体は未調査で、モデル性能の秒数や対応保証は作っていない。

## 実装と公開の完了条件

通常4K60再生、実AI動画変換、Pin/loop/zoom/backup、縦横UI、非破壊/オフラインを実装。必要なロジック試験と小さな実機素材セットを使い、測定項目はPASS/FAIL/NOT_RUNに分ける。60秒の再生だけで2〜30分すべてを保証しない。

- **正式完成版**：P0と品質ゲートの対象範囲が合格し、許諾・正本鍵・配布権限が揃う。
- **署名付きPre-release**：実装/buildを進めたうえで実機等が不足する場合に、未検証/未達を明記して納品可能。正式完成の代わりではない。
- **公開停止**：許諾、正本鍵、公開権限が不足する場合。Pre-releaseでも迂回しない。

GitHubから再取得したAPKと署名済み原本のSHA-256が一致し、APIが返す実assetの直URLを納品して初めて配布成功。private repoでは認証が必要と明記する。実機でDL/installしていない場合、その検証をしたと書かない。

## 納品物と次の操作

- `CODEX_FINAL_PROMPT.md`：自己完結した完全実装指示。単独で貼り付け可能。
- `README.md`と既存11文書：必要な章/条件を改訂、P0の機能範囲を維持。
- `CHANGELOG.md`：各文書の改訂点。
- `REFERENCE_URLS.md`：確認した一次資料と、そこから確定できない範囲。

**次は改訂ZIPをCodexへ添付し、`CODEX_FINAL_PROMPT.md`全文を貼り付ければ開始できる。** WorkでのAPKビルドは不要という依頼に従い、今回APKは作成していない。
