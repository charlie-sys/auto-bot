package com.autobot.reader
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NetworkCameraAndroidTest {
    @Test fun cropAndRotationPreserveExpectedPixelsAndBitmapOwnership(){
        val source=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        for(y in 0 until 100)for(x in 0 until 100)source.setPixel(x,y,Color.RED)
        val crop=NetworkImageTransform.apply(source,0,1f,0,0,50,100)
        val rotated=NetworkImageTransform.apply(source,90,1f,0,0,100,100)
        val zoom=NetworkImageTransform.apply(source,0,2f,0,0,100,100)
        try{
            assertEquals(100,crop.width);assertEquals(100,crop.height);assertEquals(Color.RED,crop.getPixel(50,50))
            assertEquals(100,rotated.width);assertEquals(200,rotated.height)
            assertEquals(100,zoom.width);assertEquals(50,zoom.height);assertFalse(source.isRecycled)
        }finally{source.recycle();crop.recycle();rotated.recycle();zoom.recycle()}
    }
    @Test fun liveRtmpFramesReachPhoneWithoutCameraHardwareOrModelCalls(){
        val arguments=InstrumentationRegistry.getArguments()
        val base=arguments.getString("camera_test_base")?:error("camera test base required")
        val token=arguments.getString("camera_test_token")?:error("camera test token required")
        val received=CountDownLatch(2);val valid=AtomicInteger()
        val feed=NetworkFrameClient(base,token,{bitmap,done->
            if(bitmap.width==1280&&bitmap.height==720)valid.incrementAndGet()
            bitmap.recycle();done();received.countDown()
        },{_,_->})
        try{feed.start();assertTrue("No fresh RTMP frames",received.await(12,TimeUnit.SECONDS));assertTrue(valid.get()>=2)}finally{feed.close()}
    }
}
