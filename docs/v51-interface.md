# v5.1 画面設計

## 参照と変更前

添付は指示書のテキスト1点です。指示書に記載された `LUMA_current_library.jpg` と `Bitchord_reference_now_playing.jpg` は作業領域・添付に含まれていません。下表は旧実装と指示書に書かれた構図の比較です。

| 領域 | 変更前 | Aurio v0.20 |
|---|---|---|
| ライブラリ | 「最近追加」が既定。見出しと本文に細いウェイトが表示される | 楽曲が既定。新しい順は並べ替えに移し、日本語・ラテン文字の見出しに固定Boldを使用 |
| 検索 | ライブラリ表示へ検索欄を追加 | 入力、検索対象、検索結果を専用画面に配置 |
| 再生画面 | 別画面への遷移。中央の独立したアートワーク | 上端から幅いっぱいに描画し、下部の色グラデーションへ溶け込ませる。操作は背景に重ねる |
| ミニプレイヤー | タップで別画面を開く | アートワークとコンテナの境界をドラッグに合わせて補間。短いドラッグはSpringで復帰 |
| ボトムナビ | タブごとの選択色を切り替える | 選択ガラス1枚をページ位置に同期。68dpの外形と58dpの内側ピルは半径差5dpでそろえる |

再生画面は指示書の相対配置に従い、下部に曲情報、シーク、再生操作、補助操作を配置します。横画面はアートワークと操作を左右に分けます。音量・キュー・出力経路は既存のAndroid標準機能へ接続しています。

## 主要ファイル

- `LumaScreen.kt` と `LumaGlassControls.kt` は保存可能なページ状態、IMEとの競合回避、単一の動くガラスを担当します。
- `PlayerSheet.kt`、`PlayerScreen.kt`、`PlayerArtworkBackdrop.kt` はドラッグ、開閉状態、実際のジャケットのレイヤーを担当します。
- `LibraryScreen.kt`、`SearchScreen.kt`、`SearchIndex.kt` はライブラリの表示、専用検索、検索語の正規化を担当します。
- `TrackText.kt` と `LumaTheme.kt` は1行表示、読み上げ用の全文、文字のウェイトを担当します。
- `docs/design/` は [Aurioアイコン](aurio-icon.md) のベクターマスターです。

実機の結果は [v0.20検証記録](VERIFICATION-v0.20.md) に記録します。

実装で参照した公開APIは [Compose Pager](https://developer.android.com/develop/ui/compose/layouts/pager)、[Composeのネストスクロール](https://developer.android.com/develop/ui/compose/touch-input/scroll/nested-scroll-modifiers)、[Kyant Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) です。
