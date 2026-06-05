package `in`.jphe.storyvox.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #409 — cold-launch wall-clock measurement, with and without
 * the Baseline Profile applied.
 *
 * Three parameterised runs:
 *   1. `startupNone` — `CompilationMode.None` → no AOT, every
 *      cold-launch JITs the hot path from scratch. The honest "worst
 *      case" baseline; this is what a Tab A7 Lite user sees today
 *      while running the v0.5.43 `debug` build (debug builds have AOT
 *      compilation disabled at the platform level).
 *   2. `startupBaselineProfile` — `CompilationMode.Partial(BaselineProfile)`
 *      → the bundled baseline-prof.txt is AOT-compiled before the
 *      run. This is the "with profile" number. Difference vs (1) is
 *      the headline win.
 *   3. `startupFull` — `CompilationMode.Full` → AOT-compile the entire
 *      app. Upper-bound reference; usually within 100 ms of the
 *      baseline-profile number because the BP captures the cold path.
 *      Diverges materially for apps where the hot path is genuinely
 *      narrow (e.g. media players, small utility apps). Useful as a
 *      sanity check.
 *
 * Run the full triplet:
 *   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest \
 *       -P android.testInstrumentationRunnerArguments.class=`
 *           in.jphe.storyvox.baselineprofile.StartupBenchmark`
 *
 * Reports land at:
 *   baselineprofile/build/outputs/connected_android_test_additional_output/.../
 *
 * Each method is run 5x (the macrobenchmark default; rule.measureRepeated
 * with iterations = 5) and reports min/max/median + std dev. We use
 * StartupMode.COLD so each iteration kills the process and re-launches
 * from LAUNCHER — same wall-clock measurement boundary as the manual
 * `am start -W -S` sequence from PR #494.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNone() = startup(CompilationMode.None())

    @Test
    fun startupBaselineProfile() = startup(CompilationMode.Partial())

    @Test
    fun startupFull() = startup(CompilationMode.Full())

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 5,
        startupMode = StartupMode.COLD,
        setupBlock = {
            // Each iteration: kill the target, return Home. The macrobench
            // framework handles the cold-stop intrinsically when
            // startupMode = COLD, but explicit pressHome guarantees the
            // launcher activity is the source of the next intent.
            pressHome()
        },
    ) {
        // Cold-launch the target via LAUNCHER intent. The measurement
        // boundary is "process forked" → "first frame rendered".
        startActivityAndWait()
    }

    private companion object {
        const val TARGET_PACKAGE = "org.techempower.candela"
    }
}
