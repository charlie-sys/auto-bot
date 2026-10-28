package com.autobot.reader

import kotlin.math.*

/** Small on-device log-Mel template matcher; not speech recognition or speaker identification. */
internal data class SoundTemplate(val frames:List<FloatArray>,val durationMs:Int,val recordedAt:Long=System.currentTimeMillis(),val embedding:FloatArray?=null,val categories:FloatArray?=null,val kind:String="generic")
internal object SoundFeatures {
    const val RATE=16000
    const val BANDS=20
    private const val FFT=512
    private const val WINDOW=400
    private const val HOP=160
    private val edges=IntArray(BANDS+2){i->
        val mel=2595*log10(1+7600.0/700)*i/(BANDS+1)
        ((700*(10.0.pow(mel/2595)-1))*FFT/RATE).toInt().coerceIn(0,FFT/2)
    }
    fun extract(samples:ShortArray):SoundTemplate {
        require(samples.size in RATE/12..RATE*4){"请录入 0.1—3 秒的短声音"}
        val energy=(0 until samples.size-HOP step HOP).map{start->sqrt((start until minOf(start+WINDOW,samples.size)).sumOf{val x=samples[it]/32768.0;x*x}/minOf(WINDOW,samples.size-start))}
        require(energy.isNotEmpty()){"录音太短"}
        val peak=energy.max()
        val floor=energy.sorted()[energy.size/10]
        require(peak>.012&&peak>floor*2.0){"声音太小或背景过吵，请靠近手机重录，前后保留安静"}
        val cutoff=maxOf(.006,peak*.18)
        val active=energy.indices.filter{energy[it]>=cutoff}
        require(active.isNotEmpty()){"没有检测到清晰声音"}
        val first=maxOf(0,active.first()*HOP-800)
        val last=minOf(samples.size,active.last()*HOP+WINDOW+800)
        val duration=(last-first)*1000/RATE
        require(duration in 80..3100){"请录入一次清晰短声，避免连续说话或长时间噪声"}
        val features=mutableListOf<FloatArray>()
        for(start in first..last-WINDOW step HOP){
            val real=DoubleArray(FFT);val imag=DoubleArray(FFT)
            for(i in 0 until WINDOW)real[i]=(samples[start+i]/32768.0)*(0.5-0.5*cos(2*PI*i/(WINDOW-1)))
            fft(real,imag)
            val power=DoubleArray(FFT/2+1){real[it]*real[it]+imag[it]*imag[it]}
            val mel=FloatArray(BANDS)
            for(b in 0 until BANDS){
                var sum=0.0
                val left=edges[b];val middle=maxOf(left+1,edges[b+1]);val right=maxOf(middle+1,edges[b+2])
                for(k in left until minOf(right,power.size))sum+=power[k]*(if(k<middle)(k-left).toDouble()/(middle-left) else (right-k).toDouble()/(right-middle))
                mel[b]=ln(maxOf(sum,1e-10)).toFloat()
            }
            val mean=mel.average().toFloat();var norm=0.0
            for(b in mel.indices){mel[b]-=mean;norm+=mel[b]*mel[b]}
            norm=sqrt(norm).coerceAtLeast(1e-6)
            for(b in mel.indices)mel[b]=(mel[b]/norm).toFloat()
            features.add(mel)
        }
        require(features.size>=5){"声音过短，请重录一次稍长的声音"}
        return SoundTemplate(features,duration)
    }
    fun similarity(reference:SoundTemplate,candidate:SoundTemplate):Float {
        if(reference.embedding!=null&&candidate.embedding!=null){
            val cosine=reference.embedding.indices.sumOf{reference.embedding[it].toDouble()*candidate.embedding[it]}.toFloat().coerceIn(0f,1f)
            fun categoryCosine():Float {
                val a=reference.categories?:return cosine;val b=candidate.categories?:return cosine
                var dot=0.0;var aa=0.0;var bb=0.0
                for(i in a.indices){if(i==494)continue;dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i]}
                return if(aa*bb>1e-12)(dot/sqrt(aa*bb)).toFloat().coerceIn(0f,1f)else cosine
            }
            if(reference.kind=="cough"){
                val p=candidate.categories?.getOrNull(42)?:0f
                val clearing=candidate.categories?.getOrNull(43)?:0f
                val effective=maxOf(p,clearing*.65f)
                // Category evidence is mandatory; embedding alone must not turn arbitrary speech into a cough.
                return if(effective>=.06f&&cosine>=.45f)(.73f+effective.coerceAtMost(.7f)*.35f+cosine*.05f+categoryCosine()*.03f).coerceAtMost(.99f)
                    else minOf(.69f,.5f*cosine+.3f*categoryCosine()+effective*.2f)
            }
            return (.45f*cosine+.20f*categoryCosine()+.35f*spectralSimilarity(reference,candidate)).coerceIn(0f,1f)
        }
        return spectralSimilarity(reference,candidate)
    }
    private fun spectralSimilarity(reference:SoundTemplate,candidate:SoundTemplate):Float {
        val ratio=candidate.durationMs.toDouble()/reference.durationMs
        if(ratio !in .5..1.9||reference.frames.isEmpty()||candidate.frames.isEmpty())return 0f
        val n=reference.frames.size;val m=candidate.frames.size
        var previous=DoubleArray(m+1){Double.POSITIVE_INFINITY};previous[0]=0.0
        for(i in 1..n){
            val current=DoubleArray(m+1){Double.POSITIVE_INFINITY}
            for(j in 1..m){
                var dot=0.0
                for(b in 0 until BANDS)dot+=reference.frames[i-1][b]*candidate.frames[j-1][b]
                val cost=(1-dot.coerceIn(-1.0,1.0))/2
                current[j]=cost+minOf(previous[j],current[j-1],previous[j-1])
            }
            previous=current
        }
        val distance=previous[m]/maxOf(n,m)+abs(ln(ratio))*.12
        return (1-distance*2.5).toFloat().coerceIn(0f,1f)
    }
    private fun fft(real:DoubleArray,imag:DoubleArray){
        var j=0
        for(i in 1 until FFT){var bit=FFT shr 1;while(j and bit!=0){j=j xor bit;bit=bit shr 1};j=j xor bit;if(i<j){val r=real[i];real[i]=real[j];real[j]=r}}
        var length=2
        while(length<=FFT){
            val angle=-2*PI/length;val stepR=cos(angle);val stepI=sin(angle)
            for(start in 0 until FFT step length){var wr=1.0;var wi=0.0
                for(k in 0 until length/2){val even=start+k;val odd=even+length/2;val tr=real[odd]*wr-imag[odd]*wi;val ti=real[odd]*wi+imag[odd]*wr
                    real[odd]=real[even]-tr;imag[odd]=imag[even]-ti;real[even]+=tr;imag[even]+=ti
                    val next=wr*stepR-wi*stepI;wi=wr*stepI+wi*stepR;wr=next}
            };length=length shl 1
        }
    }
}

internal class SoundEventSegmenter {
    private var floor=.003
    private var quiet=0
    private var armed=false
    private var warmup=0
    private val active=ShortArray(SoundFeatures.RATE*3+1024)
    private var size=0
    fun reset(){size=0;quiet=0;armed=false;warmup=0}
    fun feed(samples:ShortArray,eligible:Boolean=true):ShortArray? {
        val rms=sqrt(samples.sumOf{val x=it/32768.0;x*x}/samples.size.coerceAtLeast(1))
        if(!eligible){reset();return null}
        if(warmup<SoundFeatures.RATE/2){floor=(floor*.8+rms*.2).coerceIn(.001,.1);warmup+=samples.size;quiet=0;armed=false;return null}
        val loud=rms>maxOf(.008,floor*3.0)
        if(size==0){
            if(!loud){floor=(floor*.96+rms*.04).coerceIn(.001,.1);quiet+=samples.size;if(quiet>=SoundFeatures.RATE/3)armed=true;return null}
            if(!armed)return null
            active.fill(0,0,800);size=800;quiet=0
        }
        val count=minOf(samples.size,active.size-size);samples.copyInto(active,size,0,count);size+=count
        quiet=if(loud)0 else quiet+samples.size
        if(quiet>=4800||size>=SoundFeatures.RATE*3){val result=active.copyOf(size);reset();return result}
        return null
    }
}

internal class SoundTriggerGate(private val cooldownMs:Long=3000){
    private var last:Long?=null
    fun accept(now:Long,score:Float,threshold:Float,eligible:Boolean):Boolean {
        if(!eligible||!score.isFinite()||score<threshold||last?.let{now-it<cooldownMs}==true)return false
        last=now;return true
    }
}
