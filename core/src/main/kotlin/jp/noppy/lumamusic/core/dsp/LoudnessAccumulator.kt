package jp.noppy.lumamusic.core.dsp
import java.util.Locale
import kotlin.math.*

data class TagGain(val gainDb:Double,val peak:Double?,val kind:String)
fun parseGainTags(tags:Map<String,String>,album:Boolean,target:Double):TagGain? {
    val values=tags.mapKeys{it.key.uppercase(Locale.ROOT)};val scope=if(album)"ALBUM"else "TRACK"
    val r128=values["R128_${scope}_GAIN"]?.takeIf{it.length<=6&&it.matches(Regex("[+-]?[0-9]{1,6}"))}?.toIntOrNull()?.takeIf{it in -32768..32767}
    // RFC7845: signed Q7.8 dB, relative to -23 LUFS, in addition to decoder OpusHead gain.
    if(r128!=null && target.isFinite())return TagGain((r128/256.0+target+23).coerceIn(-24.0,6.0),null,"R128 Q7.8 / -23 LUFS基準")
    val gain=values["REPLAYGAIN_${scope}_GAIN"]?.trim()?.replace(Regex("\\s*dB$",RegexOption.IGNORE_CASE),"")?.trim()?.toDoubleOrNull()?.takeIf{it.isFinite()}?:return null
    val peak=values["REPLAYGAIN_${scope}_PEAK"]?.toDoubleOrNull()?.takeIf{it.isFinite()&&it>0}
    // ReplayGain is a gain at its native 89dB reference, not a LUFS measurement.
    return TagGain(gain.coerceIn(-24.0,6.0),peak,"ReplayGain / 89 dB基準（LUFS目標は非適用）")
}

/** Streaming mono/stereo BS.1770-inspired estimator, no certification or true-peak claim.
 * Uses reference K weighting, 400ms/75% overlap and absolute/relative gating.
 */
class LoudnessAccumulator(private val rate:Int,private val channels:Int){
    init{require(rate in 8000..192000 && channels in 1..2)}
    private fun atRate(c:Coeff):Coeff {
        val t=rate/48000.0
        fun transform(b0:Double,b1:Double,b2:Double):DoubleArray{val a=b0-b1+b2;val b=2*(b0-b2);val d=b0+b1+b2
            return doubleArrayOf(a*t*t+b*t+d,-2*a*t*t+2*d,a*t*t-b*t+d)}
        val n=transform(c.b0,c.b1,c.b2);val d=transform(1.0,c.a1,c.a2)
        return Coeff(n[0]/d[0],n[1]/d[0],n[2]/d[0],d[1]/d[0],d[2]/d[0])
    }
    // ITU-R BS.1770-5 Annex1 Tables1/2 (48kHz); bilinear frequency transform at other rates.
    private val shelf=Array(channels){Filter(atRate(Coeff(1.53512485958697,-2.69169618940638,1.19839281085285,-1.69065929318241,.73248077421585)))}
    private val highPass=Array(channels){Filter(atRate(Coeff(1.0,-2.0,1.0,-1.99004745483398,.99007225036621)))}
    private val tone=Array(channels){Array(10){i->val f=EQ_FREQUENCIES[i];val w=2*PI*min(f,rate*.47)/rate;val alpha=sin(w)/(2*1.2);Filter(Coeff(alpha/(1+alpha),0.0,-alpha/(1+alpha),-2*cos(w)/(1+alpha),(1-alpha)/(1+alpha)))}}
    private val toneEnergy=DoubleArray(10)
    private val window=max(1,(rate*.4).roundToInt());private val step=max(1,(rate*.1).roundToInt())
    private val ring=DoubleArray(window);private var sum=0.0;private var frames=0L;private var peak=0.0
    private val blocks=ArrayList<Double>()
    fun frame(frame:FloatArray){
        var energy=0.0
        for(ch in 0 until channels){val x=frame.getOrElse(ch){0f}.toDouble().takeIf{it.isFinite()}?:0.0;peak=max(peak,abs(x));val y=highPass[ch].sample(shelf[ch].sample(x));energy+=y*y
            for(i in 0..9){val t=tone[ch][i].sample(x);toneEnergy[i]+=t*t}}
        val index=(frames%window).toInt();sum+=energy-ring[index];ring[index]=energy;frames++
        if(frames>=window && (frames-window)%step==0L)blocks.add(max(0.0,sum/window))
    }
    fun result(complete:Boolean):TrackAnalysis {
        fun loudness(energy:Double)= - .691+10*log10(energy.coerceAtLeast(1e-30))
        val absolute=blocks.filter{loudness(it)> -70};val relative=if(absolute.isEmpty()) -70.0 else loudness(absolute.average())-10
        val gated=absolute.filter{loudness(it)>relative}
        val lufs=if(gated.isEmpty())null else loudness(gated.average()).takeIf{it.isFinite()}
        val auto=MutableList(10){0f}
        if(lufs!=null && peak>1e-5){val mid=(toneEnergy[4]+toneEnergy[5]+toneEnergy[6])/3
            fun excess(indices:IntRange){val avg=indices.map{toneEnergy[it]}.average();val db=10*log10((avg+1e-20)/(mid+1e-20));val cut= -((db-10)/6).coerceIn(0.0,2.0).toFloat();for(i in indices)if(EQ_FREQUENCIES[i]<rate*.48)auto[i]=cut}
            excess(0..3);excess(7..9)
        }
        return TrackAnalysis(lufs,peak,auto,frames,rate,complete)
    }
}
