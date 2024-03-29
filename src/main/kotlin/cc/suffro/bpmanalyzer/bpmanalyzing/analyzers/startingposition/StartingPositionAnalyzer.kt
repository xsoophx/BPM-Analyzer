package cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.startingposition

import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.AnalyzerParams
import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.BpmAnalyzer
import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.CacheAnalyzer
import cc.suffro.bpmanalyzer.bpmanalyzing.data.Bpm
import cc.suffro.bpmanalyzer.bpmanalyzing.filters.CombFilterOperations
import cc.suffro.bpmanalyzer.bpmanalyzing.filters.DifferentialRectifier
import cc.suffro.bpmanalyzer.bpmanalyzing.filters.LowPassFilter
import cc.suffro.bpmanalyzer.fft.FFTProcessor
import cc.suffro.bpmanalyzer.fft.data.FFTData
import cc.suffro.bpmanalyzer.getHighestPowerOfTwo
import cc.suffro.bpmanalyzer.wav.data.FileReader
import cc.suffro.bpmanalyzer.wav.data.Wav
import java.nio.file.Path

class StartingPositionAnalyzer(
    private val analyzer: BpmAnalyzer,
    private val wavReader: FileReader<Wav>,
    private val combFilterOperations: CombFilterOperations,
    private val fftProcessor: FFTProcessor,
) : CacheAnalyzer<Wav, StartingPosition> {
    override fun analyze(data: Wav): StartingPosition {
        val bpm = analyzer.analyze(data)
        return analyze(data, bpm)
    }

    override fun getPathAndAnalyze(path: String): StartingPosition {
        return getPathAndAnalyze(Path.of(path))
    }

    override fun getPathAndAnalyze(path: Path): StartingPosition {
        val wav = wavReader.read(path)
        val bpm = analyzer.analyze(wav)
        return analyze(wav, bpm)
    }

    override fun analyze(
        data: Wav,
        params: AnalyzerParams,
    ): StartingPosition {
        val samplesToSkip = getBassProfileUntilSeconds(2.0, data)
        val bpm = (params as StartingPositionCacheAnalyzerParams).bpm
        return analyze(data, bpm, samplesToSkip)
    }

    private fun analyze(
        data: Wav,
        bpm: Bpm,
        samplesToSkip: Int = 0,
    ): StartingPosition {
        val size = data.defaultChannel().size - samplesToSkip
        val fftResult = fftOfFirstSamples(bpm, data, samplesToSkip)
        val differentials = differentialsOf(fftResult, data)
        val frequencySum = sumOfFrequencyEnergies(differentials)

        val result = multiplySignals(frequencySum, size, bpm, data)

        return result.withIndex().maxBy { it.value }.let {
            StartingPosition(it.index + samplesToSkip, (it.index + samplesToSkip).toDouble() / data.sampleRate)
        }
    }

    private fun fftOfFirstSamples(
        bpm: Bpm,
        data: Wav,
        samplesToSkip: Int,
    ): FFTData {
        val firstSamples =
            combFilterOperations.getRelevantSamples(
                bpm,
                data.sampleRate,
                data.dataChunk.data.first().drop(samplesToSkip).toDoubleArray(),
            )
        val highestPowerOfTwo = getHighestPowerOfTwo(firstSamples.size)

        return fftProcessor.process(firstSamples.asSequence().take(highestPowerOfTwo), data.sampleRate)
    }

    private fun differentialsOf(
        fftResult: FFTData,
        data: Wav,
    ): List<List<Double>> {
        val signals = combFilterOperations.getFrequencyBands(fftResult, fftProcessor)
        val lowPassFiltered =
            signals.map {
                LowPassFilter(fftProcessor).process(it, data.fmtChunk)
            }

        return lowPassFiltered.map { DifferentialRectifier.process(it).toList() }.toList()
    }

    private fun sumOfFrequencyEnergies(differentials: List<List<Double>>): List<Double> {
        val differentialSizeX = differentials.first().size
        val differentialSizeY = differentials.size
        val frequencySum = MutableList(differentialSizeX) { 0.0 }

        for (i in 0 until differentialSizeX) {
            for (j in 0 until differentialSizeY) {
                frequencySum[i] += differentials[j][i]
            }
        }
        return frequencySum
    }

    private fun multiplySignals(
        frequencySum: List<Double>,
        size: Int,
        bpm: Bpm,
        data: Wav,
    ): DoubleArray {
        val filledFilter = combFilterOperations.getFilledFilter(size, bpm, data.sampleRate)
        val result = DoubleArray(size)

        for (i in frequencySum.indices) {
            val slice = frequencySum.subList(i, frequencySum.size)

            val sum =
                slice.mapIndexed { index, value ->
                    val product = value * filledFilter[index]
                    product * product
                }.sum()

            result[i] = sum
        }
        return result
    }

    // Just experimental for now
    private fun getBassProfileUntilSeconds(
        seconds: Double = SILENCE_ANALYZING_DURATION,
        data: Wav,
    ): Int {
        val lowPassFilter = LowPassFilter(fftProcessor)

        val bassProfile =
            data.defaultChannel().take(131072)
                .let { samples ->
                    val fftResult = fftProcessor.process(samples, data.sampleRate)
                    val bassBand = combFilterOperations.getBassBand(fftResult, fftProcessor)
                    // val lowPassFiltered = lowPassFilter.process(bassBand, data.fmtChunk)
                    DifferentialRectifier.process(bassBand)
                }

        val max = bassProfile.max()
        val skipUntil = bassProfile.indexOfFirst { it > 0.001 }

        return skipUntil
    }

    companion object {
        const val SILENCE_ANALYZING_DURATION = 2.0
    }
}
