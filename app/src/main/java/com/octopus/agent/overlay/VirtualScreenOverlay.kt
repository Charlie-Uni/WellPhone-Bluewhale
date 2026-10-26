package com.octopus.agent.overlay

import android.content.Context
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.*
import com.octopus.core.*
import com.octopus.agent.R

private class DragHandle(context: Context) : TextView(context) {
    override fun performClick(): Boolean { super.performClick();return true }
}

/** App owns the displays; this non-focusable window only views/controls an existing session. */
class VirtualScreenOverlay(private val context: Context, private val callbacks: Callbacks) {
    data class Item(val id: String, val label: String, val state: SessionState, val frame: Bitmap?,
        val frameAt: Long, val width: Int, val height: Int)
    interface Callbacks {
        fun takeover(id: String)
        fun handBack(id: String)
        fun touch(id: String, transform: PreviewTransform, effect: PreviewEffect)
        fun cancelTouch()
        fun editText(target: TouchTarget)
        fun applyText(draft: HumanTextDraft, fieldId: String, value: String)
        fun cancelText()
        fun openWorkbench()
    }
    private val manager = context.getSystemService(WindowManager::class.java)
    private var items = emptyList<Item>()
    private var selected: String? = null
    private var root: LinearLayout? = null
    private var preview: Preview? = null
    private var tabs: LinearLayout? = null
    private var stateText: TextView? = null
    private var control: Button? = null
    private var textButton: Button? = null
    private var textTarget: TouchTarget? = null
    private var textDraftId: String? = null
    private var editor: LocalTextEditor? = null
    private var params: WindowManager.LayoutParams? = null
    private var enlarged = false
    private var version = 0L
    private var lastBinding: Pair<SessionToken, Owner>? = null
    private var notice: String? = null
    private var registered = false
    private val configurations = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            if(root==null) return
            resetInput();resize()
            runCatching { manager.updateViewLayout(root,params) }.onFailure { hide() }
        }
        override fun onLowMemory() { }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun item() = items.firstOrNull { it.id == selected }
    fun visibleSessionId(): String? = if (root != null) item()?.id else null
    fun update(next: List<Item>) {
        items = next
        if (selected !in next.map { it.id }) { resetInput(); selected = next.firstOrNull()?.id; lastBinding = null }
        val binding = item()?.state?.let { it.token to it.owner }
        if (lastBinding != binding) { resetInput(); lastBinding = binding; notice = null }
        if (root != null) render()
    }
    fun show(id: String? = null): Boolean {
        if (!Settings.canDrawOverlays(context)) { hide(); return false }
        if (id != null && items.any { it.id == id } && selected != id) {
            resetInput(); selected = id; lastBinding = item()?.state?.let { it.token to it.owner }; notice = null
        }
        if (selected == null) selected = items.firstOrNull()?.id
        if (root != null) { render(); return true }
        val column = LinearLayout(context).apply {
            this.id = R.id.overlay_root
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(245,243,238)); cornerRadius = dp(16).toFloat() }
            elevation = dp(8).toFloat()
        }
        val header = DragHandle(context).apply {
            this.id = R.id.overlay_header
            text = "✳ 八爪鱼  ·  拖动此栏移动"; textSize = 13f; setTextColor(Color.rgb(32,44,41))
            setPadding(dp(4),dp(6),dp(4),dp(6))
        }
        column.addView(header)
        header.setOnTouchListener(object : View.OnTouchListener {
            private var x = 0f; private var y = 0f; private var left = 0; private var top = 0
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { resetInput(); x=event.rawX;y=event.rawY;left=params?.x?:0;top=params?.y?:0 }
                    MotionEvent.ACTION_MOVE -> params?.let {
                        val bounds=manager.currentWindowMetrics.bounds
                        it.x=(left+event.rawX-x).toInt().coerceIn(0,(bounds.width()-it.width).coerceAtLeast(0))
                        it.y=(top+event.rawY-y).toInt().coerceIn(dp(24),(bounds.height()-it.height).coerceAtLeast(dp(24)))
                        root?.let { window -> manager.updateViewLayout(window,it) }
                    }
                    MotionEvent.ACTION_UP -> view.performClick()
                }
                return true
            }
        })
        tabs=LinearLayout(context).also { column.addView(it) }
        stateText=TextView(context).apply { this.id=R.id.overlay_status;textSize=12f;setTextColor(Color.DKGRAY) }.also { column.addView(it) }
        preview=Preview(context).apply {
            this.id=R.id.overlay_preview;contentDescription="当前任务虚拟屏预览，双击放大或缩小"
        }.also { column.addView(it,LinearLayout.LayoutParams(-1,0,1f)) }
        control=Button(context).also { button ->
            button.id=R.id.overlay_control
            button.textSize=12f
            button.setOnClickListener {
                resetInput()
                item()?.let { if(it.state.owner==Owner.HUMAN) callbacks.handBack(it.id) else if(it.state.owner==Owner.AGENT) callbacks.takeover(it.id) }
            }
            column.addView(button,LinearLayout.LayoutParams(-1,dp(44)))
        }
        textButton=Button(context).apply {
            this.id=R.id.overlay_text;text="输入文字";textSize=12f
            setOnClickListener {
                resetInput()
                val current=item()?:return@setOnClickListener
                val mapping=preview?.transform()?:return@setOnClickListener
                val target=TouchTarget(current.state.token,current.state.displayId,current.state.packageName,
                    mapping.version,current.width,current.height)
                if(!isCurrent(target)) { showNotice("预览已过期，请等待新画面");return@setOnClickListener }
                textTarget=target;render();callbacks.editText(target)
            }
        }.also { column.addView(it,LinearLayout.LayoutParams(-1,dp(42))) }
        val footer=LinearLayout(context)
        fun footerButton(id: Int, text: String, action: () -> Unit) {
            footer.addView(Button(context).apply { this.id=id;this.text=text;textSize=11f;setPadding(0,0,0,0);setOnClickListener { action() } },
                LinearLayout.LayoutParams(0,dp(42),1f))
        }
        footerButton(R.id.overlay_resize,"放大/缩小") { toggleSize() }
        footerButton(R.id.overlay_settings,"确认/设置") { resetInput();callbacks.openWorkbench() }
        footerButton(R.id.overlay_hide,"隐藏") { hide() }
        column.addView(footer)
        val window=WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START;x=dp(12);y=dp(90) }
        root=column;params=window
        resize()
        return try {
            manager.addView(column,window)
            if(!registered) { context.registerComponentCallbacks(configurations);registered=true }
            render();true
        } catch (_: Exception) {
            hide();false
        }
    }
    private fun render() {
        tabs?.let { row ->
            // Preserve an in-progress button gesture across frequent frame updates.
            val keys=items.joinToString { it.id }
            if(row.tag!=keys) {
                row.removeAllViews();row.tag=keys
                items.forEach { candidate -> row.addView(Button(context).apply {
                    text=candidate.label;textSize=11f;setPadding(0,0,0,0)
                    setOnClickListener { if(selected!=candidate.id){
                        resetInput();selected=candidate.id
                        lastBinding=item()?.state?.let { it.token to it.owner };notice=null;render()
                    } }
                },LinearLayout.LayoutParams(0,dp(40),1f)) }
            }
        }
        val current=item()
        val owner=current?.state?.owner
        val status=when(owner) {
            Owner.AGENT -> "${current.label} · 只读 · 双击画面放大"
            Owner.DRAINING -> "等待已提交操作结束，暂不接受触摸"
            Owner.HUMAN -> "${current.label} · 已接管 · 双击放大 / 单指操作"
            else -> "没有可操作会话 · 请到设置创建 A/B"
        }
        stateText?.text = notice?.let { "$status\n$it" } ?: status
        control?.apply {
            text=if(owner==Owner.HUMAN) "交还 Agent（重新观察后继续）" else "原地接管当前任务"
            isEnabled=owner==Owner.AGENT || owner==Owner.HUMAN
        }
        textButton?.isEnabled=owner==Owner.HUMAN && textTarget==null
        preview?.refresh(current)
    }
    private fun resize() {
        val bounds=manager.currentWindowMetrics.bounds
        val insets=manager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val availableWidth=(bounds.width()-insets.left-insets.right-dp(24)).coerceAtLeast(1)
        val availableHeight=(bounds.height()-insets.top-insets.bottom-dp(80)).coerceAtLeast(1)
        params?.let {
            it.width=if(enlarged) availableWidth else minOf(dp(300),availableWidth)
            it.height=if(enlarged) availableHeight else minOf(dp(510),availableHeight)
            it.x=it.x.coerceIn(insets.left,(bounds.width()-it.width-insets.right).coerceAtLeast(insets.left))
            it.y=it.y.coerceIn(insets.top,(bounds.height()-it.height-insets.bottom).coerceAtLeast(insets.top))
        }
    }
    private fun toggleSize() {
        resetInput();enlarged=!enlarged;resize()
        val window=root?:return;val layout=params?:return
        manager.updateViewLayout(window,layout)
    }
    private fun resetInput() {
        version++;preview?.reset();callbacks.cancelTouch()
        textTarget=null;textDraftId=null;editor?.close();editor=null;callbacks.cancelText()
    }
    fun cancelTextInput() { resetInput();if(root!=null) render() }
    fun isTextCurrent(target: TouchTarget) = textTarget==target && isCurrent(target)
    fun showTextEditor(draft: HumanTextDraft) {
        if(!isTextCurrent(draft.target)) return
        editor?.close();textDraftId=draft.id
        editor=LocalTextEditor(context,draft,{ field,value ->
            if(isTextCurrent(draft.target) && textDraftId==draft.id) callbacks.applyText(draft,field,value)
            else finishTextEdit(draft.target,"输入已失效，请重新选择")
        },{ finishTextEdit(draft.target,"已取消文字修改") })
        if(editor?.show()!=true) finishTextEdit(draft.target,"无法打开本地编辑窗口")
    }
    fun finishTextEdit(target: TouchTarget, message: String) {
        if(textTarget!=target) return
        resetInput();showNotice(message)
    }
    fun showNotice(message: String) { notice=message;if(root!=null) render() }
    fun hide() {
        resetInput()
        root?.let { runCatching { manager.removeView(it) } }
        root=null;preview=null;tabs=null;stateText=null;control=null;textButton=null;params=null
        if(registered) { context.unregisterComponentCallbacks(configurations);registered=false }
        // Viewing window closure leaves HUMAN ownership intact. Never return automatically.
    }
    fun isCurrent(target: TouchTarget): Boolean {
        val current=item()?:return false
        val transform=preview?.transform()?:return false
        return root!=null && current.id==target.token.sessionId && current.state.token==target.token &&
            current.state.owner==Owner.HUMAN && current.state.displayId==target.displayId &&
            current.width==target.width && current.height==target.height && transform.version==target.viewportVersion &&
            SystemClock.uptimeMillis()-current.frameAt in 0..2_500
    }
    private inner class Preview(context: Context):View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val imageBounds=RectF()
        private val gestures=PreviewGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
            ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat())
        private var current: Item?=null
        private var blocked=false
        private val tick=Runnable { dispatch(gestures.tick(SystemClock.uptimeMillis())) }
        fun transform():PreviewTransform? {
            val source=current?:return null
            if(width<=0 || height<=0 || source.frame==null || source.frame.width!=source.width || source.frame.height!=source.height) return null
            return PreviewTransform(version.coerceAtLeast(1),width,height,source.width,source.height)
        }
        fun refresh(next:Item?) {
            if(current?.frame?.width!=next?.frame?.width || current?.frame?.height!=next?.frame?.height) {
                resetInput()
            }
            current=next;invalidate()
        }
        fun reset() { gestures.cancel();removeCallbacks(tick);blocked=true }
        override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
            super.onSizeChanged(w,h,oldw,oldh)
            if(w!=oldw || h!=oldh) resetInput()
        }
        override fun onDraw(canvas:Canvas) {
            canvas.drawColor(Color.rgb(26,30,29))
            val frame=current?.frame
            val mapping=transform()
            if(frame!=null && mapping!=null) {
                imageBounds.set(mapping.left,mapping.top,mapping.left+mapping.displayWidth*mapping.scale,
                    mapping.top+mapping.displayHeight*mapping.scale)
                canvas.drawBitmap(frame,null,imageBounds,paint)
            }
            else { paint.color=Color.WHITE;paint.textSize=dp(13).toFloat();canvas.drawText("等待真实虚拟屏画面",dp(12).toFloat(),dp(40).toFloat(),paint) }
        }
        private fun dispatch(effects:List<PreviewEffect>) {
            for(effect in effects) {
                if(effect==PreviewEffect.ToggleSize) { toggleSize();continue }
                val item=current?:continue
                val mapping=transform()?:continue
                if(item.state.owner==Owner.HUMAN) callbacks.touch(item.id,mapping,effect)
            }
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(textTarget!=null) return true // Text capture/editing owns the local interaction channel.
            val now=SystemClock.uptimeMillis()
            val mapping=transform()
            val point=TouchPoint(event.x,event.y)
            if(event.actionMasked==MotionEvent.ACTION_DOWN) blocked=false
            if(blocked) return true
            if(event.pointerCount!=1 || event.actionMasked==MotionEvent.ACTION_CANCEL || mapping?.map(point)==null) {
                gestures.cancel();callbacks.cancelTouch();removeCallbacks(tick);blocked=true;return true
            }
            val effects=when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> gestures.down(point,now)
                MotionEvent.ACTION_MOVE -> gestures.move(point,now)
                MotionEvent.ACTION_UP -> gestures.up(point,now)
                else -> emptyList()
            }
            dispatch(effects)
            if(event.actionMasked==MotionEvent.ACTION_DOWN || event.actionMasked==MotionEvent.ACTION_UP) {
                removeCallbacks(tick);postDelayed(tick,if(event.actionMasked==MotionEvent.ACTION_DOWN) 500 else 300)
            }
            if(event.actionMasked==MotionEvent.ACTION_UP) performClick()
            return true
        }
        override fun performClick():Boolean { super.performClick();return true }
        override fun onDetachedFromWindow() { reset();super.onDetachedFromWindow() }
    }
}
