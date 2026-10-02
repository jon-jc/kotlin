package com.roam.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class RoamBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test
    fun coldStartup() =
        benchmark.measureRepeated(
            packageName = "com.roam.app",
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.res("discovery_feed")), 10_000))
        }

    @Test
    fun discoveryScroll() =
        benchmark.measureRepeated(
            packageName = "com.roam.app",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            iterations = 3,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                check(device.wait(Until.hasObject(By.res("discovery_feed")), 10_000))
            },
        ) {
            val feed = device.findObject(By.res("discovery_feed"))
            feed.setGestureMargin(device.displayWidth / 5)
            repeat(2) {
                feed.fling(Direction.DOWN)
                device.waitForIdle()
            }
            repeat(2) {
                feed.fling(Direction.UP)
                device.waitForIdle()
            }
        }
}
