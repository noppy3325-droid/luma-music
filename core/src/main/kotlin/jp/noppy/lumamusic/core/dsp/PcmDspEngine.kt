package jp.noppy.lumamusic.core.dsp

import kotlin.math.*

fun normalizationGainDb(lufs: Double?, target: Double): Double =
    if (lufs == null || !lufs.isFinite() || !target.isFinite()) 0.0 else (target - lufs).coerceIn(-24.0, 6.0)

/** RBJ peaking filter, Q=1.2. Each channel owns its delay state. */
internal data class Coeff(val b0:Double=1.0,val b1:Double=0.0,val b2:Double=0.0,val a1:Double=0.0,val a2:Double=0.0) {
    fun response(frequency:Double,rate:Int):Double {
        val w=2*PI*frequency/rate
        val nr=b0+b1*cos(w)+b2*cos(2*w); val ni= -b1*sin(w)-b2*sin(2*w)
        val dr=1+a1*cos(w)+a2*cos(2*w); val di= -a1*sin(w)-a2*sin(2*w)
        return 10*log10(((nr*nr+ni*ni)/(dr*dr+di*di)).coerceAtLeast(1e-30))
    }
}
internal fun peakCoeff(rate:Int,frequency:Double,gain:Double):Coeff {
    if (frequency>=rate*.48 || abs(gain)<1e-7) return Coeff()
    val a=10.0.pow(gain/40); val w=2*PI*frequency/rate; val alpha=sin(w)/(2*1.2)
    val a0=1+alpha/a
    return Coeff((1+alpha*a)/a0,-2*cos(w)/a0,(1-alpha*a)/a0,-2*cos(w)/a0,(1-alpha/a)/a0)
}
internal class Filter(private val c:Coeff) {
    private var z1=0.0; private var z2=0.0
    fun sample(x:Double):Double { val y=c.b0*x+z1; z1=c.b1*x-c.a1*y+z2; z2=c.b2*x-c.a2*y
        if (!y.isFinite() || !z1.isFinite() || !z2.isFinite()) {z1=0.0;z2=0.0;return 0.0};return y }
}

/** Audio-thread owned. Configure only at buffer boundaries with an immutable snapshot.
 * Changes crossfade complete old/new filter outputs over 20ms; Direct settles to byte bypass.
 */
class PcmDspEngine(val sampleRate:Int,val channels:Int) {
    private class Bank(val config:DspConfig,analysis:TrackAnalysis?,rate:Int,channels:Int) {
        val gains=List(10) { i ->
            if(EQ_FREQUENCIES[i]>=rate*.48)return@List 0.0
            val manual=if(config.eqEnabled)config.bands[i].toDouble() else 0.0
            // Manual EQ wins. Auto only adjusts neutral bands, never adds to a manual boost.
            val auto=if(config.autoEnabled && abs(manual)<.5)analysis?.toneGains?.getOrNull(i)?.toDouble()?.takeIf{it.isFinite()}?.coerceIn(-2.0,2.0)?:0.0 else 0.0
            (manual+auto).coerceIn(-12.0,12.0)
        }
        val coeffs=gains.mapIndexed { i,g -> peakCoeff(rate,EQ_FREQUENCIES[i],g) }
        private val activeCoeffs=coeffs.filterIndexed{i,_->abs(gains[i])>1e-7}
        val filters=Array(channels){Array(activeCoeffs.size){i->Filter(activeCoeffs[i])}}
        val normalize=if(config.normalizeEnabled)(analysis?.tagGainDb?.takeIf{it.isFinite()}?.coerceIn(-24.0,6.0)?:normalizationGainDb(analysis?.estimatedLufs,config.targetLufs.toDouble()))else 0.0
        val headroom=gains.sumOf { max(it,0.0) } + if(gains.any{it>0})1.0 else 0.0
        private val widthHeadroom=if(config.widthEnabled && channels==2)20*log10(max(1.0,config.width.toDouble()))else 0.0
        private val desired=config.preampDb+normalize-headroom-widthHeadroom
        private val peakLimit=analysis?.peak?.takeIf{it.isFinite()&&it>0}?.let{-1-20*log10(it)}?:0.0
        // Without a measured peak, positive normalization never assumes space above full scale.
        val gainDb=if(desired>0 || analysis?.peak?.let{it.isFinite()&&it>1}==true)min(desired,peakLimit)else desired
        val gain=10.0.pow(gainDb/20)
        fun sample(x:Float,ch:Int):Double {var y=if(x.isFinite())x.toDouble()*gain else 0.0; for(f in filters[ch])y=f.sample(y);return y}
    }
    private var current:Bank?=null
    private var previous:Bank?=null
    private var ramp=0
    private val rampLength=max(1,sampleRate/50)
    val isDirect:Boolean get()=current?.config?.mode!=SoundMode.ENHANCE && ramp==0
    val transitioning:Boolean get()=ramp>0
    val gainDb:Double get()=current?.gainDb?:0.0
    fun configure(config:DspConfig,analysis:TrackAnalysis?=null) {
        val next=Bank(config.safe(),analysis,sampleRate,channels)
        if(current!=null){ previous=current; ramp=rampLength }
        current=next
    }
    fun responseDb(frequency:Double):Double = current?.coeffs?.sumOf{it.response(frequency,sampleRate)}?:0.0
    private fun render(bank:Bank?,frame:FloatArray,out:DoubleArray) {
        if(bank==null || bank.config.mode==SoundMode.DIRECT){for(ch in 0 until channels)out[ch]=frame[ch].toDouble();return}
        for(ch in 0 until channels)out[ch]=bank.sample(frame[ch],ch)
        if(channels==2 && bank.config.widthEnabled){val mid=(out[0]+out[1])*.5;val side=(out[0]-out[1])*.5*bank.config.width;out[0]=mid+side;out[1]=mid-side}
    }
    private val fresh=DoubleArray(channels)
    private val old=DoubleArray(channels)
    fun processFrame(frame:FloatArray) {
        if(isDirect)return
        render(current,frame,fresh)
        if(ramp>0)render(previous,frame,old)
        val mix=if(ramp>0)1.0-ramp.toDouble()/rampLength else 1.0
        for(ch in 0 until channels) {
            val y=if(ramp>0)old[ch]*(1-mix)+fresh[ch]*mix else fresh[ch]
            frame[ch]=(if(y.isFinite())y.coerceIn(-1.0,32767.0/32768)else 0.0).toFloat()
        }
        if(ramp>0 && --ramp==0)previous=null
    }
    fun process(input:FloatArray):FloatArray {
        if(isDirect)return input.copyOf()
        val out=input.copyOf();val frame=FloatArray(channels)
        for(offset in 0..(out.size-channels) step channels){for(ch in 0 until channels)frame[ch]=out[offset+ch];processFrame(frame);for(ch in 0 until channels)out[offset+ch]=frame[ch]}
        return out
    }
}
