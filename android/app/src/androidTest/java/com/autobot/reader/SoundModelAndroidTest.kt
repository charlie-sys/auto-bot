package com.autobot.reader

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SoundModelAndroidTest {
    @Test fun bundledModelProducesFiniteNormalizedEmbeddingsWithoutMicrophone(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val samples=ShortArray(24000){i->val t=i-4000;if(t in 0..10000)(sin(2*PI*700*t/16000)*sin(PI*t/10000)*12000).toInt().toShort()else 0}
        SoundModelExtractor(context).use{model->
            val result=model.extract(samples)
            assertEquals(1024,result.embedding!!.size);assertEquals(521,result.categories!!.size)
            assertTrue(result.embedding.all{it.isFinite()});assertTrue(result.categories.all{it.isFinite()&&it in 0f..1f})
            assertEquals(1.0,result.embedding.sumOf{it.toDouble()*it},.005)
            assertTrue(SoundFeatures.similarity(result,model.extract(samples))>.98f)
            val cough=result.copy(kind="cough")
            val notCough=result.copy(categories=FloatArray(521))
            assertTrue("Embedding alone cannot satisfy cough mode",SoundFeatures.similarity(cough,notCough)<.7f)
        }
    }
}
