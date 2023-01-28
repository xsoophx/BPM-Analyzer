package cc.suffro.fft.bpmanalyzing

import cc.suffro.fft.bpmanalyzing.data.SeparatedSignals
import cc.suffro.fft.bpmanalyzing.filters.CombFilter
import cc.suffro.fft.bpmanalyzing.filters.DifferentialRectifier
import cc.suffro.fft.bpmanalyzing.filters.Filterbank
import cc.suffro.fft.bpmanalyzing.filters.LowPassFilter
import cc.suffro.fft.fft.FFTProcessor
import cc.suffro.fft.fft.data.FFTData
import cc.suffro.fft.fft.data.Window
import cc.suffro.fft.fft.data.WindowFunction
import cc.suffro.fft.getHighestPowerOfTwo
import cc.suffro.fft.wav.data.FmtChunk
import cc.suffro.fft.wav.data.Wav
import java.lang.StrictMath.abs
import java.lang.StrictMath.min
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class BpmAnalyzer(private val fftProcessor: FFTProcessor = FFTProcessor()) {

    fun analyzeByPeakDistance(
        wav: Wav,
        start: Double = 0.0,
        end: Double = 10.0,
        interval: Double = 0.01,
        windowFunction: WindowFunction? = null
    ): Double {
        val windows = wav.getWindows(start = start, end = min(wav.timestampLastSample, end), interval = interval)

        val averagePeakTimes = fftProcessor
            .process(windows, samplingRate = wav.sampleRate, windowFunction = windowFunction)
            .getBassFrequencyBins(interval)
            .getIntervalsOverTime()
            .getAveragePeakTimes()

        val averagePeakDistance = averagePeakTimes.map { it.sorted().getAveragePeakDistance() }.average()
        return (60 / averagePeakDistance).round()
    }

    private fun Sequence<FFTData>.getBassFrequencyBins(interval: Double): Sequence<Peak> {
        val firstElement = first()
        val lowerFrequencyBin = maxOf(0, firstElement.binIndexOf(LOWER_FREQUENCY_BOUND))
        val higherFrequencyBin = minOf(firstElement.binIndexOf(HIGHER_FREQUENCY_BOUND), firstElement.bins.count - 1)

        return mapIndexed { index, fftData ->
            Peak(
                midPoint = index * interval,
                interval = interval,
                values = fftData.magnitudes.subList(lowerFrequencyBin, higherFrequencyBin + 1)
            )
        }
    }

    fun analyzeByEnergyLevels(
        wav: Wav,
        start: Double = 0.0,
        end: Double = 3.0,
        windowFunction: WindowFunction? = null
    ): Double {
        val timeFrame = end - start
        require(timeFrame >= 2.2) { "Timeframe needs to be at least 2.2 seconds long for analyzing BPM." }

        val window = wav.getWindow(start = start, numSamples = MINIMUM_FFT_SIZE_BY_ENERGY_LEVELS)
        val fftResult = fftProcessor.process(window, wav.sampleRate, windowFunction = windowFunction)
        val filterParams = FilterParams(wav.fmtChunk, fftResult.duration, timeFrame)

        return fftResult.analyzeSingleWindow(LowPassFilter(fftProcessor), CombFilter(fftProcessor), filterParams)
    }

    private fun FFTData.analyzeSingleWindow(
        lowPassFilter: LowPassFilter,
        combFilter: CombFilter,
        filterParams: FilterParams
    ): Double {
        // transforms the signal into multiple signals, split by frequency intervals
        val separatedSignals = Filterbank.separateSignals(this, MAXIMUM_FREQUENCY)
        val timeTransformed = separatedSignals.transformToTimeDomain(filterParams.interval)

        val bassBand = timeTransformed.first()

        return bassBand.applyFilters(lowPassFilter, combFilter, filterParams)
    }

    private fun Window.applyFilters(
        lowPassFilter: LowPassFilter,
        combFilter: CombFilter,
        filterParams: FilterParams
    ): Double {
        val lowPassFiltered = lowPassFilter.process(this, filterParams.fmtChunk)
        val differentials = DifferentialRectifier.process(lowPassFiltered)
        val combFiltered = combFilter.process(differentials, filterParams.fmtChunk.sampleRate)

        return combFiltered.toDouble()
    }

    private fun SeparatedSignals.transformToTimeDomain(interval: Double): Sequence<Window> {
        // TODO: add better handling for low frequencies, don't cut information
        val signalInTimeDomain =
            fftProcessor.processInverse(
                values.asSequence().map {
                    val powerOfTwo = getHighestPowerOfTwo(it.size)
                    it.asSequence().take(powerOfTwo)
                }
            )

        return signalInTimeDomain.map { Window(it, interval) }
    }

    private fun Sequence<Peak>.getIntervalsOverTime(): List<List<PeakInterval>> =
        first().values.indices.map { bin ->
            map { peak ->
                PeakInterval(midPoint = peak.midPoint, magnitude = peak.values[bin])
            }.toList()
        }

    private fun List<List<PeakInterval>>.getAveragePeakTimes(): List<List<Double>> {
        val averagePeaks = List(size) { mutableListOf<Double>() }

        forEachIndexed { i, intervals ->
            val maxMagnitude = intervals.maxOf { it.magnitude }
            intervals
                .filter { it.magnitude > maxMagnitude / 2 }
                .sortedByDescending { it.magnitude }
                .forEach { interval ->
                    addPeakTime(averagePeaks[i], interval.midPoint)
                }
        }
        return averagePeaks
    }

    private fun addPeakTime(peakTimes: MutableList<Double>, time: Double) {
        val e = 0.05
        val index = peakTimes.indexOfFirst { (abs(it - time)) < e }

        if (index != -1) {
            peakTimes[index] = (peakTimes[index] + time) / 2
        } else if (peakTimes.all { abs(it - time) > MAX_PEAK_DISTANCE }) {
            peakTimes += time
        }
    }

    private fun List<Double>.getAveragePeakDistance(): Double =
        asSequence().zipWithNext().map { (current, next) -> next - current }.average()

    private fun Double.round(): Double =
        DecimalFormat("#.#", DecimalFormatSymbols(Locale.US))
            .apply {
                roundingMode = RoundingMode.CEILING
            }
            .format(this)
            .toDouble()

    private data class Peak(
        val midPoint: Double,
        val interval: Double,
        val values: List<Double>
    )

    private data class PeakInterval(
        val midPoint: Double,
        val magnitude: Double
    )

    data class FilterParams(
        val fmtChunk: FmtChunk,
        val interval: Double,
        val timeFrame: Double
    )

    companion object {
        private const val LOWER_FREQUENCY_BOUND = 40.0
        private const val HIGHER_FREQUENCY_BOUND = 120.0
        private const val MAX_PEAK_DISTANCE = 60.0 / 220.0
        private const val MAXIMUM_FREQUENCY = 4096

        // assuming the first kick is starting at 0.0s
        // 60 bpm minimum bpm, one interval would be 2 seconds + ~ 0.3s buffer
        // minimum FFT size is determined by sampling frequency (minBpm = 60)
        // (1.0 / minBpm * 60 * samplingRate) = samplingRate
        // CombFilter has three pulses: 1, 44100, 88200 (-1 offset)
        private const val MINIMUM_FFT_SIZE_BY_ENERGY_LEVELS = 131072
    }
}
