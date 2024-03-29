package cc.suffro.bpmanalyzer.speedadjustment

import org.koin.core.qualifier.named
import org.koin.dsl.module

val speedAdjusterModule =
    module {
        single(named("ProdSpeedAdjuster")) { SpeedAdjuster(get(named("ProdImpl"))) }
    }
