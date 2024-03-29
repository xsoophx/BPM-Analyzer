package cc.suffro.bpmanalyzer.bpmanalyzing.analyzers

import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.combfilter.CombFilterAnalyzer
import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.combfilter.CombFilterCacheAnalyzer
import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.combfilter.CombFilterCacheAnalyzerTestImpl
import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.startingposition.StartingPosition
import cc.suffro.bpmanalyzer.bpmanalyzing.analyzers.startingposition.StartingPositionAnalyzer
import cc.suffro.bpmanalyzer.data.TrackInfo
import cc.suffro.bpmanalyzer.wav.data.Wav
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.named
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val analyzerTestModule =
    module {
        singleOf(::CombFilterAnalyzer) {
            bind<BpmAnalyzer>()
        }

        singleOf(::CombFilterCacheAnalyzerTestImpl) {
            bind<CacheAnalyzer<Wav, TrackInfo>>()
            named("TestImpl")
        }

        singleOf(::StartingPositionAnalyzer) {
            bind<CacheAnalyzer<Wav, StartingPosition>>()
        }
        singleOf(::CombFilterCacheAnalyzer) {
            bind<CacheAnalyzer<Wav, TrackInfo>>()
            named("ProdImpl")
        }

        singleOf(::StartingPositionAnalyzer)
    }
