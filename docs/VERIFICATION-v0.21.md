# Aurio v0.21

v5.2の指示を、v5.1のボトムナビとプレイヤー開閉の修正を含む作業ツリーに適用しました。

| 主な変更 | ファイル |
|---|---|
| 指の移動方向を一度だけ判定し、曲送りのプレビューと確定を制御 | `MiniPlayerMotion.kt`、`LumaGlassControls.kt`、`PlayerSheet.kt` |
| Media3の曲送り、シャッフル順、リピート状態、キュー編集と復元 | `PlaybackController.kt`、`PlaybackService.kt`、`QueueOrder.kt`、`SettingsRepository.kt` |
| アートワークの切り抜きとグラデーション、再生モードと操作の配置 | `PlayerScreen.kt`、`PlayerArtworkBackdrop.kt` |
| ボトムUIの計測とリスト下端の余白 | `LumaScreen.kt`、`LibraryScreen.kt`、`SearchScreen.kt` |

シャッフルはMedia3の順序を使用します。シャッフル中に「次に再生」を選ぶと、現在曲の直後に入り、「キューに追加」は末尾に入ります。元の順序はシャッフルを解除するか、キューの「元の順序を編集」から確認できます。リピート1曲は自動進行に適用し、前後の曲がある場合は手動で移動できます。UIはアプリ内の再生モードと再生順をMediaSessionから取得します。

参照画像は添付されていなかったため、旧版の実画面と指示書の記述を基に調整しました。今回の検証にはAndroidエミュレーターを使用します。Xiaomi 14Tの描画時間、EarPodsの着脱・リモコン、Bluetooth実機、HyperIsland固有の表示は未検証です。

## スクリーンショット

テスト用に生成した同じ楽曲・ジャケットを使いました。

| 画面 | 修正前 | 修正後 |
|---|---|---|
| ライブラリ | [v5.1 UI修正版](screenshots/v021/before/01-library-light.png) | [v0.21](screenshots/v021/after/01-library-light.png) |
| Now Playing | [v5.1 UI修正版](screenshots/v021/before/04-player-light.png) | [v0.21](screenshots/v021/after/04-player-light.png) |

[前の曲へのドラッグ](screenshots/v021/after/20-mini-previous-drag.png)、[次の曲へのドラッグ](screenshots/v021/after/21-mini-next-drag.png)、[ボトムメニューのドラッグ](screenshots/v021/after/19-bottom-navigation-drag.png)、[ライブラリの最終行](screenshots/v021/after/22-final-library-row.png)。

## ビルドとテスト

- 単体テスト39件が通過しました。ミニプレイヤーの方向判定、移動量・速度での確定、プレイヤー開閉のキャンセルを含みます。release APKとテスト用APKを生成し、Lintはエラー0、警告23、ヒント1でした。
- 最終署名済みAPKをAndroid 17 / API 37エミュレーターに上書きし、インストルメンテーションテスト14件がすべて通過しました（176.264秒）。44.1/48 kHzの合成PCM、メディア通知、DSP切替、DB移行、差分スキャンの回帰を含みます。
- 画面テストで左右の曲送り、横ドラッグのキャンセル、キュー末尾での送り、上・下スワイプでの開閉、ボトムタブの往復、最終行のメニュー、シークと再生・キューの継続を確認しました。
- Media3のテストは合成WAVを実際に再生し、Repeat OFFの終了、ALLの周回、ONEの同一曲ループ、ONE中の手動送り、シャッフル中の前後移動、手動キュー追加、順序と再生モードの保存を確認します。

署名済みAPKは従来と同じ開発用証明書を使用しています。versionNameは `0.21`、versionCodeは `21` です。SHA-256は `a0b6f5d053b7bad115886234e852cb221a21d1a55af9fdf602bee0ddac4b1edb` です。

実装で参照した資料は [Media3 Player API](https://developer.android.com/reference/androidx/media3/common/Player)、[Media3のキューとシャッフル](https://developer.android.com/media/media3/exoplayer/playlists)、[Composeのジェスチャー](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)、[Composeのインセット](https://developer.android.com/develop/ui/compose/system/insets-ui) です。
