package cc.suffro.fft.bpmanalyzing.data

import org.kotlinmath.Complex

typealias SeparatedSignals = Map<Interval, List<Complex>>
typealias Signal = Sequence<Double>

data class Interval(
    val lowerBound: Int,
    val upperBound: Int
)