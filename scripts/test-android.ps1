$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$runId = [guid]::NewGuid().ToString("N")
# 编译产物放到系统临时目录，而不是项目的 .tmp。
# 放在项目里的话，每跑一次就多一个带 guid 的目录，几十次之后 .tmp 里全是垃圾，
# 而且 Windows 上 java 刚退出时文件句柄可能还没释放，脚本自己删不干净。
$outDir = Join-Path $env:TEMP "ziji-android-test-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Push-Location $root
try {
    $sources = @(
        "android-companion\src\main\java\com\ziji\ledger\LedgerCodec.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerCategories.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerMigrationReport.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerRepositorySnapshot.java",
        "android-companion\src\main\java\com\ziji\ledger\LegacyLedgerSnapshot.java",
        "android-companion\src\main\java\com\ziji\ledger\LegacyLedgerMigrator.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerRepository.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerAppStorage.java",
        "android-companion\src\main\java\com\ziji\ledger\BridgeSettings.java",
        "android-companion\src\main\java\com\ziji\ledger\ListenerStatus.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationListenerStatusStore.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationListenerRecoveryReceiver.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationSample.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationSampleStore.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationMatcher.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationTextBuilder.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationDeduper.java",
        "android-companion\src\main\java\com\ziji\ledger\NotificationCaptureProcessor.java",
        "android-companion\src\main\java\com\ziji\ledger\CaptureEvent.java",
        "android-companion\src\main\java\com\ziji\ledger\CaptureHistory.java",
        "android-companion\src\main\java\com\ziji\ledger\PendingCaptureQueue.java",
        "android-companion\src\main\java\com\ziji\ledger\PendingCaptureFlusher.java",
        "android-companion\src\main\java\com\ziji\ledger\BridgeEndpoint.java",
        "android-companion\src\main\java\com\ziji\ledger\BridgeSetupText.java",
        "android-companion\src\main\java\com\ziji\ledger\BridgeClient.java",
        "android-companion\src\main\java\com\ziji\ledger\SharedCaptureText.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerEntry.java",
        "android-companion\src\main\java\com\ziji\ledger\TransactionParser.java",
        "android-companion\src\main\java\com\ziji\ledger\MerchantDictionary.java",
        "android-companion\src\main\java\com\ziji\ledger\PurposeResolver.java",
        "android-companion\src\main\java\com\ziji\ledger\PaymentPageMatcher.java",
        "android-companion\src\main\java\com\ziji\ledger\LocalLedger.java",
        "android-companion\src\main\java\com\ziji\ledger\RefundOffset.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerCsvExporter.java",
        "android-companion\src\main\java\com\ziji\ledger\LedgerCsvImporter.java",
        "android-companion\src\test\java\com\ziji\ledger\NotificationMatcherTest.java",
        "android-companion\src\test\java\com\ziji\ledger\NotificationTextBuilderTest.java",
        "android-companion\src\test\java\com\ziji\ledger\NotificationDeduperTest.java",
        "android-companion\src\test\java\com\ziji\ledger\PendingCaptureQueueTest.java",
        "android-companion\src\test\java\com\ziji\ledger\PendingCaptureFlusherTest.java",
        "android-companion\src\test\java\com\ziji\ledger\BridgeEndpointTest.java",
        "android-companion\src\test\java\com\ziji\ledger\BridgeSetupTextTest.java",
        "android-companion\src\test\java\com\ziji\ledger\BridgeClientTest.java",
        "android-companion\src\test\java\com\ziji\ledger\SharedCaptureTextTest.java",
        "android-companion\src\test\java\com\ziji\ledger\TransactionParserTest.java",
        "android-companion\src\test\java\com\ziji\ledger\PaymentPageMatcherTest.java",
        "android-companion\src\test\java\com\ziji\ledger\PurposeResolverTest.java",
        "android-companion\src\test\java\com\ziji\ledger\LocalLedgerTest.java",
        "android-companion\src\test\java\com\ziji\ledger\RefundOffsetTest.java",
        "android-companion\src\test\java\com\ziji\ledger\LedgerCategoriesTest.java",
        "android-companion\src\test\java\com\ziji\ledger\LedgerCsvExporterTest.java",
        "android-companion\src\test\java\com\ziji\ledger\LedgerCsvImporterTest.java",
        "android-companion\src\test\java\com\ziji\ledger\NotificationCaptureProcessorTest.java",
        "android-companion\src\test\java\com\ziji\ledger\CaptureHistoryTest.java",
        "android-companion\src\test\java\com\ziji\ledger\LedgerRepositoryTest.java",
        "android-companion\src\test\java\com\ziji\ledger\NotificationListenerRecoveryTest.java",
        "android-companion\src\test\java\com\ziji\ledger\NotificationSampleStoreTest.java",
        "android-companion\src\test\java\android\content\Context.java",
        "android-companion\src\test\java\android\content\SharedPreferences.java",
        "android-companion\src\test\java\android\content\Intent.java",
        "android-companion\src\test\java\android\content\BroadcastReceiver.java",
        "android-companion\src\test\java\android\content\ComponentName.java",
        "android-companion\src\test\java\android\service\notification\NotificationListenerService.java"
    )

    & javac --add-modules jdk.httpserver -encoding UTF-8 -d $outDir @sources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.NotificationMatcherTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.NotificationTextBuilderTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.NotificationDeduperTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.PendingCaptureQueueTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.PendingCaptureFlusherTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.BridgeEndpointTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.BridgeSetupTextTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java --add-modules jdk.httpserver -cp $outDir com.ziji.ledger.BridgeClientTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.SharedCaptureTextTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.TransactionParserTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.PaymentPageMatcherTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.PurposeResolverTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.LocalLedgerTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.RefundOffsetTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.LedgerCategoriesTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.LedgerCsvExporterTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.LedgerCsvImporterTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.NotificationCaptureProcessorTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.CaptureHistoryTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.LedgerRepositoryTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.NotificationListenerRecoveryTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & java -cp $outDir com.ziji.ledger.NotificationSampleStoreTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
    # 每次运行都新建一个带 guid 的编译目录。不清理的话 .tmp 会越堆越多，
    # 几十次之后连找文件都费劲——这也是「功能上的不合理」，只是发生在仓库里。
    Remove-Item -Recurse -Force -Path $outDir -ErrorAction SilentlyContinue
}
