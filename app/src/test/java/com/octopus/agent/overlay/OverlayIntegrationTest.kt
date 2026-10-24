package com.octopus.agent.overlay

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.octopus.agent.R
import com.octopus.agent.workflow.StatusCapsule
import com.octopus.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import java.time.Duration

/** Runs production Android views/handlers. Window service is Robolectric; no shell/native input. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayIntegrationTest {
    private lateinit var context: Application
    private lateinit var overlay: VirtualScreenOverlay
    private lateinit var capsule: StatusCapsule
    private val touches = mutableListOf<Triple<String, PreviewTransform, PreviewEffect>>()
    private val takeovers = mutableListOf<String>()
    private val handBacks = mutableListOf<String>()
    private var cancellations = 0
    private var workbenchOpens = 0
    private lateinit var frame: Bitmap

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        frame = Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(218,234,227))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.DKGRAY;textSize=38f }
            Canvas(this).drawText("Synthetic virtual display",40f,100f,paint)
        }
        overlay=VirtualScreenOverlay(context,object: VirtualScreenOverlay.Callbacks {
            override fun takeover(id:String) { takeovers+=id }
            override fun handBack(id:String) { handBacks+=id }
            override fun touch(id:String,transform:PreviewTransform,effect:PreviewEffect) { touches+=Triple(id,transform,effect) }
            override fun cancelTouch() { cancellations++ }
            override fun openWorkbench() { workbenchOpens++ }
        })
        capsule=StatusCapsule(context) { overlay.show() }
    }
    @After fun cleanup() { capsule.close();overlay.hide();shadowOf(Looper.getMainLooper()).idle();frame.recycle() }
    private fun item(id:String="A",owner:Owner=Owner.AGENT,epoch:Long=0) = VirtualScreenOverlay.Item(id,"$id · 任务",
        SessionState(SessionToken(id,epoch),if(id=="A") 2 else 3,
            if(id=="A") "com.google.android.gm" else "com.google.android.calendar",owner,0),
        frame,SystemClock.uptimeMillis(),720,1280)
    private fun views():List<View> = (shadowOf(context.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl).views
    private fun root():ViewGroup = views().single { it.id==R.id.overlay_root } as ViewGroup
    private fun layoutWindow() {
        shadowOf(Looper.getMainLooper()).idle()
        val root=root();val params=root.layoutParams as WindowManager.LayoutParams
        root.measure(View.MeasureSpec.makeMeasureSpec(params.width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(params.height,View.MeasureSpec.EXACTLY))
        root.layout(0,0,params.width,params.height)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun show(vararg items:VirtualScreenOverlay.Item) {
        overlay.update(items.toList());assertTrue(overlay.show());layoutWindow()
        assertTrue(root().findViewById<View>(R.id.overlay_preview).height>0)
    }
    private fun advance(millis:Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
    private fun event(view:View,action:Int,x:Float,y:Float,down:Long=SystemClock.uptimeMillis()) {
        val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        try { assertTrue(view.dispatchTouchEvent(motion)) } finally { motion.recycle() }
    }
    private fun tapPreview() {
        val view=root().findViewById<View>(R.id.overlay_preview);val x=view.width/2f;val y=view.height/2f
        val down=SystemClock.uptimeMillis();event(view,MotionEvent.ACTION_DOWN,x,y,down);advance(20)
        event(view,MotionEvent.ACTION_UP,x,y,down)
    }
    @Test fun missingPermissionCreatesNoWindow() {
        ShadowSettings.setCanDrawOverlays(false)
        overlay.update(listOf(item()));assertFalse(overlay.show())
        capsule.update("等待确认",true)
        assertTrue(views().isEmpty());assertEquals(0,workbenchOpens)
    }
    @Test fun capsuleClickOpensExistingPreviewWithoutOpeningWorkbench() {
        overlay.update(listOf(item()))
        capsule.update("正在执行",true)
        assertEquals(1,views().size)
        val label=views().single() as TextView
        val params=label.layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE!=0)
        label.performClick();layoutWindow()
        assertEquals(2,views().size);assertEquals(0,workbenchOpens);assertTrue(takeovers.isEmpty())
    }
    @Test fun readOnlyPreviewDoesNotForwardATapAndNeedsExplicitTakeover() {
        show(item());tapPreview();advance(350)
        assertTrue(touches.isEmpty())
        root().findViewById<Button>(R.id.overlay_control).performClick()
        assertEquals(listOf("A"),takeovers)
        assertTrue(handBacks.isEmpty())
    }
    @Test fun humanTapGoesToSelectedSessionWithFitCoordinates() {
        show(item(owner=Owner.HUMAN,epoch=1));tapPreview();advance(350)
        val touch=touches.single();assertEquals("A",touch.first)
        val effect=touch.third as PreviewEffect.Tap
        val mapped=requireNotNull(touch.second.map(effect.point))
        assertEquals(360f,mapped.x,0.01f);assertEquals(640f,mapped.y,0.01f)
        assertEquals(0,workbenchOpens)
    }
    @Test fun doubleTapChangesWindowSizeWithoutSendingAppTouches() {
        show(item(owner=Owner.HUMAN,epoch=1))
        val before=(root().layoutParams as WindowManager.LayoutParams).width
        tapPreview();advance(80);tapPreview();layoutWindow();advance(350)
        val after=(root().layoutParams as WindowManager.LayoutParams).width
        assertTrue(after>before);assertTrue(touches.isEmpty())
    }
    private fun preview()=root().findViewById<View>(R.id.overlay_preview)
    private fun dragStart():Pair<View,Long> {
        val view=preview();val down=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,view.width/2f,view.height/2f,down);advance(30)
        event(view,MotionEvent.ACTION_MOVE,view.width/2f,view.height/2f+60,down)
        assertTrue(touches.any {it.third is PreviewEffect.Begin})
        return view to down
    }
    private fun target(item:VirtualScreenOverlay.Item,transform:PreviewTransform) = TouchTarget(item.state.token,
        item.state.displayId,item.state.packageName,transform.version,item.width,item.height)
    @Test fun drainingDisablesTakeoverAndDoesNotForwardTouches() {
        show(item(owner=Owner.DRAINING,epoch=1))
        assertFalse(root().findViewById<Button>(R.id.overlay_control).isEnabled)
        tapPreview();advance(350)
        assertTrue(touches.isEmpty());assertTrue(takeovers.isEmpty());assertTrue(handBacks.isEmpty())
    }
    @Test fun switchingSessionsDropsPendingTapBeforeRoutingToB() {
        show(item(owner=Owner.HUMAN,epoch=1),item("B",Owner.HUMAN,1))
        tapPreview();advance(80)
        fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap {descendants(view.getChildAt(it))} else emptyList()
        (descendants(root()).filterIsInstance<Button>().single {it.text=="B · 任务"}).performClick()
        advance(350);assertTrue(touches.isEmpty())
        tapPreview();advance(350)
        assertEquals("B",touches.single().first)
        assertEquals(3,item("B").state.displayId)
    }
    @Test fun hideDropsPendingTapAndReopenRetainsHumanOwnership() {
        show(item(owner=Owner.HUMAN,epoch=1));tapPreview();advance(80)
        root().findViewById<Button>(R.id.overlay_hide).performClick();advance(350)
        assertTrue(views().isEmpty());assertTrue(touches.isEmpty());assertTrue(handBacks.isEmpty())
        assertTrue(overlay.show());layoutWindow()
        assertTrue(root().findViewById<Button>(R.id.overlay_control).text.startsWith("交还"))
        root().findViewById<Button>(R.id.overlay_control).performClick()
        assertEquals(listOf("A"),handBacks)
    }
    @Test fun hideDuringDragCancelsAndIgnoresLateFingerUp() {
        val source=item(owner=Owner.HUMAN,epoch=1);show(source)
        val (view,down)=dragStart();val mapping=touches.first().second
        val before=cancellations;val touchCount=touches.size
        root().findViewById<Button>(R.id.overlay_hide).performClick();advance(10)
        event(view,MotionEvent.ACTION_UP,view.width/2f,view.height/2f+60,down);advance(350)
        assertTrue(cancellations>before);assertEquals(touchCount,touches.size)
        assertFalse(overlay.isCurrent(target(source,mapping)));assertTrue(handBacks.isEmpty())
    }
    @Test fun resizeDuringDragInvalidatesMappingButKeepsWindowAndSession() {
        val source=item(owner=Owner.HUMAN,epoch=1);show(source)
        val original=root();dragStart();val mapping=touches.first().second
        val before=cancellations
        root().findViewById<Button>(R.id.overlay_resize).performClick();layoutWindow()
        assertSame(original,root());assertTrue(cancellations>before)
        assertFalse(overlay.isCurrent(target(source,mapping)))
        touches.clear();tapPreview();advance(350)
        assertEquals("A",touches.single().first);assertNotEquals(mapping.version,touches.single().second.version)
    }
    @Test fun freshFramesDoNotInterruptAnActiveGesture() {
        show(item(owner=Owner.HUMAN,epoch=1));val (view,down)=dragStart()
        val before=cancellations;val version=touches.first().second.version
        overlay.update(listOf(item(owner=Owner.HUMAN,epoch=1)));advance(20)
        event(view,MotionEvent.ACTION_MOVE,view.width/2f,view.height/2f+80,down)
        event(view,MotionEvent.ACTION_UP,view.width/2f,view.height/2f+80,down)
        assertEquals(before,cancellations)
        assertTrue(touches.last().third is PreviewEffect.End)
        assertTrue(touches.all {it.second.version==version})
    }
    @Test fun epochChangeInvalidatesOldMappingAndQueuedTap() {
        val source=item(owner=Owner.HUMAN,epoch=1);show(source);tapPreview();advance(350)
        val old=target(source,touches.single().second);assertTrue(overlay.isCurrent(old))
        touches.clear();tapPreview();advance(80)
        overlay.update(listOf(item(owner=Owner.HUMAN,epoch=3)));advance(350)
        assertFalse(overlay.isCurrent(old));assertTrue(touches.isEmpty())
    }
    @Test fun systemCancelStopsDragWithoutAnEndOrTap() {
        show(item(owner=Owner.HUMAN,epoch=1));val (view,down)=dragStart()
        val before=cancellations;val count=touches.size
        event(view,MotionEvent.ACTION_CANCEL,view.width/2f,view.height/2f,down);advance(600)
        assertTrue(cancellations>before);assertEquals(count,touches.size)
    }
    @Test fun secondFingerCancelsInsteadOfBecomingAnotherGesture() {
        show(item(owner=Owner.HUMAN,epoch=1));val (view,down)=dragStart()
        val count=touches.size;val before=cancellations
        val props=Array(2){i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coords=Array(2){i->MotionEvent.PointerCoords().apply {x=view.width/2f+i*20;y=view.height/2f;pressure=1f;size=1f}}
        val two=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),
            2,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
        try {assertTrue(view.dispatchTouchEvent(two))}finally {two.recycle()}
        event(view,MotionEvent.ACTION_UP,view.width/2f,view.height/2f,down);advance(600)
        assertTrue(cancellations>before);assertEquals(count,touches.size)
    }
    @Test fun permissionRevocationRemovesPreviewAndCancelsPendingTap() {
        show(item(owner=Owner.HUMAN,epoch=1));tapPreview()
        ShadowSettings.setCanDrawOverlays(false);assertFalse(overlay.show());advance(350)
        assertTrue(views().isEmpty());assertTrue(touches.isEmpty());assertTrue(handBacks.isEmpty())
    }
    @Test fun windowsRequestNoFocusAndOnlySettingsButtonOpensWorkbench() {
        overlay.update(listOf(item()));capsule.update("执行中",true)
        views().single().performClick();layoutWindow()
        val params=root().layoutParams as WindowManager.LayoutParams
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE!=0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL!=0)
        capsule.update("",false);assertEquals(1,views().size);assertEquals(0,workbenchOpens)
        root().findViewById<Button>(R.id.overlay_settings).performClick()
        assertEquals(1,workbenchOpens)
    }
    @Test fun configurationChangeCancelsOldMappingAndKeepsWindowInsideBounds() {
        val source=item(owner=Owner.HUMAN,epoch=1);show(source);dragStart()
        val old=target(source,touches.first().second);val before=cancellations
        RuntimeEnvironment.setQualifiers("w915dp-h412dp-land-xhdpi")
        context.onConfigurationChanged(Configuration(context.resources.configuration));layoutWindow()
        val params=root().layoutParams as WindowManager.LayoutParams
        val bounds=context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        assertTrue(cancellations>before);assertFalse(overlay.isCurrent(old))
        assertTrue(params.x>=0 && params.y>=0 && params.x+params.width<=bounds.width() && params.y+params.height<=bounds.height())
    }
    @Test fun stalePreviewFrameCannotAuthorizeInput() {
        val source=item(owner=Owner.HUMAN,epoch=1);show(source);tapPreview();advance(350)
        val binding=target(source,touches.single().second)
        assertTrue(overlay.isCurrent(binding));advance(2_501)
        assertFalse(overlay.isCurrent(binding))
    }
    private fun snapshot(name:String) {
        val root=root();val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val preview=preview()
            assertEquals(Color.rgb(218,234,227),bitmap.getPixel(preview.left+preview.width/2,preview.top+preview.height/2))
            val directory=File(requireNotNull(System.getProperty("octopus.overlay.artifacts"))).apply {mkdirs()}
            File(directory,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        } finally {bitmap.recycle()}
    }
    @Test fun nativeGraphicsRendersCompactAndExpandedSyntheticPreviews() {
        show(item(owner=Owner.HUMAN,epoch=1),item("B"));snapshot("compact")
        root().findViewById<Button>(R.id.overlay_resize).performClick();layoutWindow();snapshot("expanded")
        val root=root()
        for(id in listOf(R.id.overlay_control,R.id.overlay_resize,R.id.overlay_settings,R.id.overlay_hide)) {
            val child=root.findViewById<View>(id);assertTrue(child.width>0 && child.height>0)
        }
    }
}
