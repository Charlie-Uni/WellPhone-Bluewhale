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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.widget.Spinner
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
            override fun editText(target:TouchTarget) { textRequests+=target }
            override fun applyText(draft:HumanTextDraft,fieldId:String,value:String) { textWrites+=Triple(draft,fieldId,value) }
            override fun cancelText() { }
            override fun openWorkbench() { workbenchOpens++ }
        })
        capsule=StatusCapsule(context) { overlay.show() }
    }
    private val textRequests=mutableListOf<TouchTarget>()
    private val textWrites=mutableListOf<Triple<HumanTextDraft,String,String>>()
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
    private fun requestText():TouchTarget {
        root().findViewById<Button>(R.id.overlay_text).performClick()
        return textRequests.last()
    }
    private fun draft(target:TouchTarget,fields:List<HumanTextField> = listOf(HumanTextField("title","标题","旧标题",true))) =
        HumanTextDraft("local-draft",target,fields)
    private fun editor():ViewGroup = views().single {it.id==R.id.text_editor_root} as ViewGroup
    private fun openEditor(fields:List<HumanTextField> = listOf(HumanTextField("title","标题","旧标题",true))):HumanTextDraft {
        val draft=draft(requestText(),fields);overlay.showTextEditor(draft);advance(1)
        val editor=editor();val params=editor.layoutParams as WindowManager.LayoutParams
        editor.measure(View.MeasureSpec.makeMeasureSpec(params.width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.AT_MOST))
        editor.layout(0,0,params.width,editor.measuredHeight);advance(1)
        return draft
    }
    @Test fun textEntryRequiresHumanOwnership() {
        show(item());assertFalse(root().findViewById<Button>(R.id.overlay_text).isEnabled)
        overlay.update(listOf(item(owner=Owner.DRAINING,epoch=1)))
        assertFalse(root().findViewById<Button>(R.id.overlay_text).isEnabled);assertTrue(textRequests.isEmpty())
        overlay.update(listOf(item(owner=Owner.HUMAN,epoch=1)));requestText()
        assertEquals(1,textRequests.size)
    }
    @Test fun explicitEditorIsFocusableWhilePreviewKeepsItsFlags() {
        show(item(owner=Owner.HUMAN,epoch=1));openEditor()
        val params=editor().layoutParams as WindowManager.LayoutParams
        assertEquals(0,params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        assertTrue((root().layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE!=0)
        assertEquals("旧标题",editor().findViewById<EditText>(R.id.text_editor_value).text.toString())
        assertEquals(0,workbenchOpens)
    }
    @Test fun chineseMultilineEditIsExplicitAndDoubleConfirmationOnlySubmitsOnce() {
        show(item(owner=Owner.HUMAN,epoch=1));val draft=openEditor()
        val value="中文面试 🐙\n改为 18:30"
        editor().findViewById<EditText>(R.id.text_editor_value).setText(value)
        assertTrue(textWrites.isEmpty());val confirm=editor().findViewById<Button>(R.id.text_editor_apply)
        confirm.performClick();confirm.performClick()
        assertEquals(listOf(Triple(draft,"title",value)),textWrites)
        assertTrue(handBacks.isEmpty())
    }
    @Test fun unfocusedMultipleFieldsRequireExplicitSelectionAndSupportClearing() {
        show(item(owner=Owner.HUMAN,epoch=1))
        openEditor(listOf(HumanTextField("title","标题","原标题",false),HumanTextField("notes","备注","原备注",false)))
        assertFalse(editor().findViewById<Button>(R.id.text_editor_apply).isEnabled)
        editor().findViewById<Spinner>(R.id.text_editor_field).setSelection(2);advance(1)
        val input=editor().findViewById<EditText>(R.id.text_editor_value)
        assertEquals("原备注",input.text.toString());input.setText("")
        editor().findViewById<Button>(R.id.text_editor_apply).performClick()
        assertEquals("notes",textWrites.single().second);assertEquals("",textWrites.single().third)
    }
    @Test fun textRequestCancelsPendingTapAndBlocksPreviewUntilClosed() {
        show(item(owner=Owner.HUMAN,epoch=1));tapPreview();openEditor();advance(350)
        tapPreview();advance(350);assertTrue(touches.isEmpty())
        editor().findViewById<Button>(R.id.text_editor_cancel).performClick()
        assertEquals(1,views().size);assertTrue(textWrites.isEmpty())
        layoutWindow() // Closing notice changes preview size; settle its invalidating traversal first.
        tapPreview();advance(350);assertEquals(1,touches.size)
    }
    @Test fun hiddenPreviewRejectsLateReadAndOldEditorConfirm() {
        show(item(owner=Owner.HUMAN,epoch=1));val draft=openEditor()
        val confirm=editor().findViewById<Button>(R.id.text_editor_apply)
        overlay.hide();confirm.performClick();overlay.showTextEditor(draft)
        assertTrue(views().isEmpty());assertTrue(textWrites.isEmpty());assertTrue(handBacks.isEmpty())
    }
    @Test fun switchingSessionsInvalidatesEditorWithoutApplyingChanges() {
        show(item(owner=Owner.HUMAN,epoch=1),item("B",Owner.HUMAN,1));val draft=openEditor()
        overlay.show("B");layoutWindow();overlay.showTextEditor(draft)
        assertEquals(1,views().size);assertFalse(overlay.isTextCurrent(draft.target));assertTrue(textWrites.isEmpty())
    }
    @Test fun resizeAndEpochChangeBothCloseEditor() {
        show(item(owner=Owner.HUMAN,epoch=1));val first=openEditor()
        root().findViewById<Button>(R.id.overlay_resize).performClick();layoutWindow()
        assertEquals(1,views().size);assertFalse(overlay.isTextCurrent(first.target))
        val second=openEditor();overlay.update(listOf(item(owner=Owner.AGENT,epoch=2)))
        assertEquals(1,views().size);assertFalse(overlay.isTextCurrent(second.target));assertTrue(textWrites.isEmpty())
    }
    @Test fun handbackClosesEditorAndStaleConfirmCannotApply() {
        show(item(owner=Owner.HUMAN,epoch=1));openEditor()
        val confirm=editor().findViewById<Button>(R.id.text_editor_apply)
        root().findViewById<Button>(R.id.overlay_control).performClick();confirm.performClick()
        assertEquals(listOf("A"),handBacks);assertTrue(textWrites.isEmpty());assertEquals(1,views().size)
    }
    @Test fun editorBackDiscardsDraftAndDoesNotHandBackOrWrite() {
        show(item(owner=Owner.HUMAN,epoch=1));val draft=openEditor()
        val input=editor().findViewById<EditText>(R.id.text_editor_value)
        input.setText("尚未写回")
        input.onKeyPreIme(KeyEvent.KEYCODE_BACK,KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK))
        assertEquals(1,views().size);assertFalse(overlay.isTextCurrent(draft.target))
        assertTrue(textWrites.isEmpty());assertTrue(handBacks.isEmpty());assertEquals("",input.text.toString())
    }
    @Test fun editorCompletionClosesLocalWindowAndRetainsHumanControl() {
        show(item(owner=Owner.HUMAN,epoch=1));val draft=openEditor()
        overlay.finishTextEdit(draft.target,"文字已核实")
        assertEquals(1,views().size);assertTrue(root().findViewById<TextView>(R.id.overlay_status).text.contains("文字已核实"))
        assertEquals("交还 Agent（重新观察后继续）",root().findViewById<Button>(R.id.overlay_control).text.toString())
        assertTrue(handBacks.isEmpty())
    }
    @Test fun nativeGraphicsRendersLocalTextEditor() {
        show(item(owner=Owner.HUMAN,epoch=1));openEditor()
        val editor=editor();val bitmap=Bitmap.createBitmap(editor.width,editor.height,Bitmap.Config.ARGB_8888)
        try {
            editor.draw(Canvas(bitmap))
            val directory=File(requireNotNull(System.getProperty("octopus.overlay.artifacts"))).apply {mkdirs()}
            File(directory,"text-editor.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            assertTrue(editor.findViewById<View>(R.id.text_editor_value).height>0)
            assertTrue(editor.findViewById<View>(R.id.text_editor_apply).bottom<=editor.height)
        } finally {bitmap.recycle()}
    }
}
