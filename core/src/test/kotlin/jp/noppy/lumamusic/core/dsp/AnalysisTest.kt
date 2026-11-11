package jp.noppy.lumamusic.core.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AnalysisTest {
    @Test fun kWeighted997HzReferenceAndRateConversion(){for(rate in listOf(44100,48000)){val a=LoudnessAccumulator(rate,1);val f=FloatArray(1);repeat(rate*2){f[0]=(.1*sin(2*PI*997*it/rate)).toFloat();a.frame(f)};assertEquals(-23.01,a.result(true).estimatedLufs!!,.15)}}
    @Test fun silentPartialAndNonFiniteHaveNoFabricatedLoudness(){val a=LoudnessAccumulator(48000,2);repeat(48000){a.frame(floatArrayOf(Float.NaN,0f))};val r=a.result(false);assertNull(r.estimatedLufs);assertFalse(r.complete);assertTrue(r.toneGains.all{it==0f});assertEquals(0.0,r.peak,0.0)}
    @Test fun replayGainIsDbAndR128IsFixedPointNotLufs(){assertEquals(-3.2,parseGainTags(mapOf("REPLAYGAIN_TRACK_GAIN" to "-3.20 dB"),false,-16.0)!!.gainDb,1e-6);assertEquals(5.0,parseGainTags(mapOf("R128_TRACK_GAIN" to "-512"),false,-16.0)!!.gainDb,1e-6);assertNull(parseGainTags(mapOf("R128_TRACK_GAIN" to "NaN"),false,-16.0));assertNull(parseGainTags(mapOf("REPLAYGAIN_TRACK_GAIN" to "4 dB"),true,-16.0))}
    @Test fun autoOnlyRespondsConservativelyToMeasuredExcess(){val a=LoudnessAccumulator(48000,2);val f=FloatArray(2);repeat(48000){val x=(.3*sin(2*PI*62*it/48000)).toFloat();f[0]=x;f[1]=x;a.frame(f)};val r=a.result(true);assertTrue(r.toneGains.any{it<0});assertTrue(r.toneGains.all{it in -2f..2f})}
}
