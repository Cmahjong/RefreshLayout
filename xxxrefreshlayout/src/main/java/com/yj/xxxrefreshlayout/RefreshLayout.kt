package com.yj.xxxrefreshlayout

import android.content.Context
import android.graphics.Color
import android.support.v4.view.MotionEventCompat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.Scroller

/**
 * Created by AItsuki on 2016/6/13.
 * -
 */
class RefreshLayout : ViewGroup {

    private var refreshHeader: View? = null
    private var target: View? = null
    // target/header偏移距离
    private var currentTargetOffsetTop: Int = 0
    private var lastTargetOffsetTop: Int = 0

    private var hasMeasureHeader: Boolean = false   // 是否已经计算头部高度
    private val touchSlop: Int by lazy {
        ViewConfiguration.get(context).scaledTouchSlop
    }
    private var headerHeight: Int = 0       // header高度
    private var totalDragDistance: Int = 0  // 需要下拉这个距离才进入松手刷新状态，默认和header高度一致
    private var maxDragDistance: Int = 0
    private var activePointerId: Int = 0
    private var isTouch: Boolean = false
    private var hasSendCancelEvent: Boolean = false
    private var lastMotionX: Float = 0.toFloat()
    private var lastMotionY: Float = 0.toFloat()
    private var initDownY: Float = 0.toFloat()
    private var initDownX: Float = 0.toFloat()
    private var lastEvent: MotionEvent? = null
    private var mIsBeginDragged: Boolean = false
    private val autoScroll: AutoScroll by lazy {
        AutoScroll()
    }
    private var state = State.RESET
    private var refreshListener: OnRefreshListener? = null
    private var isAutoRefresh: Boolean = false


    // 刷新成功，显示500ms成功状态再滚动回顶部
    private val delayToScrollTopRunnable = Runnable { autoScroll.scrollTo(START_POSITION, SCROLL_TO_TOP_DURATION) }

    private val autoRefreshRunnable = Runnable {
        // 标记当前是自动刷新状态，finishScroll调用时需要判断
        // 在actionDown事件中重新标记为false
        isAutoRefresh = true
        changeState(State.PULL)
        autoScroll.scrollTo(totalDragDistance, SCROLL_TO_REFRESH_DURATION)
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attributes: AttributeSet) : super(context, attributes) {
        init()
    }

    private fun init() {
        // 添加默认的头部，先简单的用一个ImageView代替头部
        val imageView = ImageView(context)
        imageView.setImageResource(R.drawable.one_piece)
        imageView.setBackgroundColor(Color.BLACK)
        setRefreshHeader(imageView)
    }

    /**
     * 设置自定义header
     */
     fun setRefreshHeader(view: View?) {
        if (view != null && view != refreshHeader) {
            removeView(refreshHeader)
            // 为header添加默认的layoutParams
            var layoutParams: ViewGroup.LayoutParams? = view.layoutParams
            if (layoutParams == null) {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(context, 100f))
                view.layoutParams = layoutParams
            }
            refreshHeader = view
            addView(refreshHeader)
        }
    }

    fun setRefreshListener(refreshListener: OnRefreshListener) {
        this.refreshListener = refreshListener
    }

    fun refreshComplete() {
        changeState(State.COMPLETE)
        if (currentTargetOffsetTop == START_POSITION) {
            changeState(State.RESET)
        } else {
            if (!isTouch) {
                postDelayed(delayToScrollTopRunnable, SHOW_COMPLETED_TIME)
            }
        }
    }

    /**
     * 在onCreate中调用autoRefresh，此时View可能还没有初始化好，需要延长一段时间执行。
     *
     * @param duration 延时执行的毫秒值
     */
    fun autoRefresh(duration: Long = 500) {
        if (state != State.RESET) {
            return
        }
        postDelayed(autoRefreshRunnable, duration)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (target == null) {
            ensureTarget()
        }

        if (target == null) {
            return
        }

        // ----- measure target -----
        // target占满整屏
        target?.measure(View.MeasureSpec.makeMeasureSpec(
                measuredWidth - paddingLeft - paddingRight, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(measuredHeight - paddingTop - paddingBottom, View.MeasureSpec.EXACTLY)
        )

        // ----- measure refreshView-----
        measureChild(refreshHeader, widthMeasureSpec, heightMeasureSpec)
        if (!hasMeasureHeader) { // 防止header重复测量
            hasMeasureHeader = true
            headerHeight = refreshHeader?.measuredHeight ?: 0 // header高度
            totalDragDistance = headerHeight   // 需要pull这个距离才进入松手刷新状态
            if (maxDragDistance == 0) {  // 默认最大下拉距离为控件高度的五分之四
                maxDragDistance = totalDragDistance * 3
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = measuredWidth
        val height = measuredHeight
        if (childCount == 0) {
            return
        }

        if (target == null) {
            ensureTarget()
        }
        if (target == null) {
            return
        }

        // target铺满屏幕
        val child = target
        val childLeft = paddingLeft
        val childTop = paddingTop + currentTargetOffsetTop
        val childWidth = width - paddingLeft - paddingRight
        val childHeight = height - paddingTop - paddingBottom
        child?.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)

        // header放到target的上方，水平居中
        val refreshViewWidth = refreshHeader?.measuredWidth ?: 0
        refreshHeader?.layout(width / 2 - refreshViewWidth / 2,
                -headerHeight + currentTargetOffsetTop,
                width / 2 + refreshViewWidth / 2,
                currentTargetOffsetTop)
    }

    /**
     * 将第一个Child作为target
     */
    private fun ensureTarget() {
        if (target == null) {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child != refreshHeader) {
                    target = child
                    break
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled || target == null) {
            return super.dispatchTouchEvent(ev)
        }

        val actionMasked = ev.actionMasked // support Multi-touch
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = ev.getPointerId(0)
                isAutoRefresh = false
                isTouch = true
                hasSendCancelEvent = false
                mIsBeginDragged = false
                lastTargetOffsetTop = currentTargetOffsetTop
                currentTargetOffsetTop = target?.top ?: 0
                lastMotionX = ev.getX(0)
                initDownX = lastMotionX
                lastMotionY = ev.getY(0)
                initDownY = lastMotionY
                autoScroll.stop()
                removeCallbacks(delayToScrollTopRunnable)
                removeCallbacks(autoRefreshRunnable)
                super.dispatchTouchEvent(ev)
                return true    // return true，否则可能接受不到move和up事件
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == INVALID_POINTER) {
                    Log.e(TAG, "Got ACTION_MOVE event but don't have an active pointer id.")
                    return super.dispatchTouchEvent(ev)
                }
                lastEvent = ev
                val x = ev.getX(MotionEventCompat.findPointerIndex(ev, activePointerId))
                val y = ev.getY(MotionEventCompat.findPointerIndex(ev, activePointerId))
                val yDiff = y - lastMotionY
                val offsetY = yDiff * DRAG_RATE
                lastMotionX = x
                lastMotionY = y

                if (!mIsBeginDragged && Math.abs(y - initDownY) > touchSlop) {
                    mIsBeginDragged = true
                }

                if (mIsBeginDragged) {
                    val moveDown = offsetY > 0 // ↓
                    val canMoveDown = canChildScrollUp()
                    val moveUp = !moveDown     // ↑
                    val canMoveUp = currentTargetOffsetTop > START_POSITION

                    // 判断是否拦截事件
                    if (moveDown && !canMoveDown || moveUp && canMoveUp) {
                        moveSpinner(offsetY)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isTouch = false
                if (currentTargetOffsetTop > START_POSITION) {
                    finishSpinner()
                }
                activePointerId = INVALID_POINTER
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = MotionEventCompat.getActionIndex(ev)
                if (pointerIndex < 0) {
                    Log.e(TAG, "Got ACTION_POINTER_DOWN event but have an invalid action index.")
                    return super.dispatchTouchEvent(ev)
                }
                lastMotionX = ev.getX(pointerIndex)
                lastMotionY = ev.getY(pointerIndex)
                lastEvent = ev
                activePointerId = MotionEventCompat.getPointerId(ev, pointerIndex)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                onSecondaryPointerUp(ev)
                lastMotionY = ev.getY(ev.findPointerIndex(activePointerId))
                lastMotionX = ev.getX(ev.findPointerIndex(activePointerId))
            }
        }
        return super.dispatchTouchEvent(ev)
    }


    private fun moveSpinner(diff: Float) {
        var offset = Math.round(diff)
        if (offset == 0) {
            return
        }

        // 发送cancel事件给child
        if (!hasSendCancelEvent && isTouch && currentTargetOffsetTop > START_POSITION) {
            sendCancelEvent()
            hasSendCancelEvent = true
        }

        var targetY = Math.max(0, currentTargetOffsetTop + offset) // target不能移动到小于0的位置……
        // y = x - (x/2)^2
        val extraOS = (targetY - totalDragDistance).toFloat()
        val slingshotDist = totalDragDistance.toFloat()
        val tensionSlingshotPercent = Math.max(0f, Math.min(extraOS, slingshotDist * 2) / slingshotDist)
        val tensionPercent = (tensionSlingshotPercent - Math.pow((tensionSlingshotPercent / 2).toDouble(), 2.0)).toFloat()

        if (offset > 0) { // 下拉的时候才添加阻力
            offset = (offset * (1f - tensionPercent)).toInt()
            targetY = Math.max(0, currentTargetOffsetTop + offset)
        }

        // 1. 在RESET状态时，第一次下拉出现header的时候，设置状态变成PULL
        if (state == State.RESET && currentTargetOffsetTop == START_POSITION && targetY > 0) {
            changeState(State.PULL)
        }

        // 2. 在PULL或者COMPLETE状态时，header回到顶部的时候，状态变回RESET
        if (START_POSITION in targetY..(currentTargetOffsetTop - 1)) {
            if (state == State.PULL || state == State.COMPLETE) {
                changeState(State.RESET)
            }
        }

        // 3. 如果是从底部回到顶部的过程(往上滚动)，并且手指是松开状态, 并且当前是PULL状态，状态变成LOADING，这时候我们需要强制停止autoScroll
        if (state == State.PULL && !isTouch && currentTargetOffsetTop > totalDragDistance && targetY <= totalDragDistance) {
            autoScroll.stop()
            changeState(State.LOADING)
            if (refreshListener != null) {
                refreshListener!!.onRefresh()
            }
            // 因为判断条件targetY <= totalDragDistance，会导致不能回到正确的刷新高度（有那么一丁点偏差），调整change
            val adjustOffset = totalDragDistance - targetY
            offset += adjustOffset
        }

        setTargetOffsetTopAndBottom(offset)

        // 别忘了回调header的位置改变方法。
        if (refreshHeader is RefreshHeader) {
            (refreshHeader as RefreshHeader)
                    .onPositionChange(currentTargetOffsetTop.toFloat(), lastTargetOffsetTop.toFloat(), totalDragDistance.toFloat(), isTouch, state)

        }

    }

    private fun finishSpinner() {
        if (state == State.LOADING) {
            if (currentTargetOffsetTop > totalDragDistance) {
                autoScroll.scrollTo(totalDragDistance, SCROLL_TO_REFRESH_DURATION)
            }
        } else {
            autoScroll.scrollTo(START_POSITION, SCROLL_TO_TOP_DURATION)
        }
    }


    private fun changeState(state: State) {
        this.state = state

        //        Toast.makeText(getContext(), state.toString(), Toast.LENGTH_SHORT).show();
        val refreshHeader = if (this.refreshHeader is RefreshHeader) this.refreshHeader as RefreshHeader? else null
        if (refreshHeader != null) {
            when (state) {
                State.RESET -> refreshHeader.reset()
                State.PULL -> refreshHeader.pull()
                State.LOADING -> refreshHeader.refreshing()
                State.COMPLETE -> refreshHeader.complete()
            }
        }
    }

    private fun setTargetOffsetTopAndBottom(offset: Int) {
        if (offset == 0) {
            return
        }
        target?.offsetTopAndBottom(offset)
        refreshHeader?.offsetTopAndBottom(offset)
        lastTargetOffsetTop = currentTargetOffsetTop
        currentTargetOffsetTop = target?.top ?: 0
        //        Log.e(TAG, "moveSpinner: currentTargetOffsetTop = "+ currentTargetOffsetTop);
        invalidate()
    }

    private fun sendCancelEvent() {
        if (lastEvent == null) {
            return
        }
        val ev = MotionEvent.obtain(lastEvent)
        ev.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(ev)
    }

    private fun onSecondaryPointerUp(ev: MotionEvent) {
        val pointerIndex = MotionEventCompat.getActionIndex(ev)
        val pointerId = MotionEventCompat.getPointerId(ev, pointerIndex)
        if (pointerId == activePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            val newPointerIndex = if (pointerIndex == 0) 1 else 0
            lastMotionY = ev.getY(newPointerIndex)
            lastMotionX = ev.getX(newPointerIndex)
            activePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex)
        }
    }

    private fun canChildScrollUp(): Boolean {
        return if (target is AbsListView) {
            val absListView = target as AbsListView
            absListView.childCount > 0 && (absListView.firstVisiblePosition > 0 || absListView.getChildAt(0)
                    .top < absListView.paddingTop)
        } else {
            target!!.canScrollVertically(-1) || target!!.scrollY > 0
        }
    }


    private inner class AutoScroll : Runnable {
        private val scroller: Scroller by lazy {
            Scroller(context)
        }
        private var lastY: Int = 0


        override fun run() {
            val finished = !scroller.computeScrollOffset() || scroller.isFinished
            if (!finished) {
                val currY = scroller.currY
                val offset = currY - lastY
                lastY = currY
                moveSpinner(offset.toFloat())
                post(this)
                onScrollFinish(false)
            } else {
                stop()
                onScrollFinish(true)
            }
        }

        fun scrollTo(to: Int, duration: Int) {
            val from = currentTargetOffsetTop
            val distance = to - from
            stop()
            if (distance == 0) {
                return
            }
            scroller.startScroll(0, 0, 0, distance, duration)
            post(this)
        }

        fun stop() {
            removeCallbacks(this)
            if (!scroller.isFinished) {
                scroller.forceFinished(true)
            }
            lastY = 0
        }
    }

    /**
     * 在scroll结束的时候会回调这个方法
     *
     * @param isForceFinish 是否是强制结束的
     */
    private fun onScrollFinish(isForceFinish: Boolean) {
        if (isAutoRefresh && !isForceFinish) {
            isAutoRefresh = false
            changeState(State.LOADING)
            if (refreshListener != null) {
                refreshListener!!.onRefresh()
            }
            finishSpinner()
        }
    }

    interface OnRefreshListener {
        fun onRefresh()
    }

    enum class State {
        RESET, PULL, LOADING, COMPLETE
    }

    companion object {

        private const val TAG = "RefreshLayout"
        private const val DRAG_RATE = 0.5f
        private const val INVALID_POINTER = -1

        // scroller duration
        private const val SCROLL_TO_TOP_DURATION = 800
        private const val SCROLL_TO_REFRESH_DURATION = 250
        private const val SHOW_COMPLETED_TIME: Long = 500
        private const val START_POSITION = 0
        fun dp2px(context: Context?, dpValue: Float): Int {
            val scale = context?.resources?.displayMetrics?.density ?: 0f
            return (dpValue * scale + 0.5f).toInt()
        }
    }

}