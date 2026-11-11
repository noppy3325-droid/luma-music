# Aurio

Android 10以降で動作するオフライン音楽プレイヤー。Kotlin / Jetpack Compose / Media3を使用します。アプリIDは `jp.noppy.lumamusic` です。

[v0.20のAPKをダウンロード](https://github.com/noppy3325-droid/luma-music/releases/download/v0.20/Aurio-0.20.apk)してインストールしてください。既存版と同じ開発用署名を使用し、Roomの追加マイグレーションで登録フォルダー・お気に入り・歌詞・プレイリストを保持します。

## v0.20

- ミニプレイヤーを上にドラッグすると全面アートワークの再生画面が開き、上部を下へ引くと元の画面に戻ります。再生とキューは開閉中も維持します。
- ライブラリ、専用検索、プレイリストはタップでも横スワイプでも切り替えられます。選択ガラスはページに追従し、Backdrop 2.0.1で背景をサンプリングします。
- 曲名とアーティスト名を1行で省略表示します。Noto Sans JPの固定ウェイトと、Aurioの新しいアダプティブアイコンを採用しました。
- SAFの発見・タグ・画像/歌詞処理を分離。16曲単位の逐次表示、2並列のタグ取得、サイズ/更新日時による差分読み込み、表示中/再生中の曲だけ詳細を取得します。
- Aurio Direct / Enhance。実働する10バンドEQ、プリセット保存、ヘッドルーム、音量均一化、控えめな測定ベースのAuto補正、ステレオ幅調整を搭載しています。
- MediaSession、バックグラウンド再生、標準メディア通知、キュー、LRC/手動歌詞、複数フォルダーに対応。元の音楽ファイルを移動・削除・変換しません。

初回は音楽フォルダーを選択します。設定はライブラリ右上、サウンド設定は再生画面の下部から開けます。音声解析はユーザーが実行した場合だけ端末内で行い、通常のスキャンには含めません。

## 音声表示と制約

DirectはAurio自身のPCM加工をバイパスします。この版はMedia3の共通PCM16経路を使用するため、24bit/float音源は上流で16bitへ変換されます。Directと、DACまでのビットパーフェクト実証は別の状態です。

USB DAC / USB-C EarPods / BluetoothはAndroid標準の経路を利用します。API34以上ではOSが実際に報告するミキサー形式を照合し、条件を満たす場合だけ希望設定を試みます。取得できない機器仕様や実出力は「未確認」と表示し、ビットパーフェクトを断定しません。独自USBドライバーは含みません。

音量解析は推定LUFS・サンプルピークです。認証済みメーター、true-peakリミッター、AIによる復元ではありません。アルバム音量は全曲の有効な解析キャッシュがある場合の推定です。詳細は [DSP設計](docs/audio-dsp-v020.md)、[スキャン設計](docs/library-scan-design.md)、[ガラス描画](docs/liquid-glass-design.md) を参照してください。

Xiaomi 14T（Android16）での測定・テストと、EarPodsを含む未検証項目は [v0.20検証記録](docs/VERIFICATION-v0.20.md) に記録します。物理ボタン、USB DACのクロック、長時間再生、全コーデック、HyperIsland固有の拡張表示を検証済みとは表示しません。

## ビルド

Android SDK Platform37.0 / Build Tools36.0.0、JDK17以上（検証環境JBR25）、同梱のGradle Wrapperを使用します。SDKの場所は未追跡の `local.properties` で指定してください。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-home'
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest :app:assembleRelease
```

`assembleRelease` は未署名APKを生成します。配布には署名が必要です。秘密鍵はリポジトリに含めません。今回の配布APKは従来と同じ開発用鍵で署名した、デバッグ不可のreleaseビルドです。

実機テストはテストAPKを `adb install -r -t` で導入し、`adb shell am instrument -w jp.noppy.lumamusic.test/androidx.test.runner.AndroidJUnitRunner` で実行します。合成の無音PCMと生成画像を使用し、音量設定は変更しません。詳細な [実機手順](docs/DEVICE_TESTS.md) と [依存ライブラリの表記](THIRD_PARTY_NOTICES.md) も参照してください。
旧名はLuma Musicです。既存データの互換性のためアプリIDと内部データ名は保持しています。表示はAurioに統一し、日本語・英字とも同梱のNoto Sans JPを使用します。
