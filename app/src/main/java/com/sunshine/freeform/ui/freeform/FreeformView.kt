package com.sunshine.freeform.ui.freeform

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.view.animation.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.databinding.ViewFreeformFlymeBinding
import com.sunshine.freeform.utils.ServiceUtils.windowManager
import com.sunshine.freeform.utils.ServiceUtils.displayManager
import com.sunshine.freeform.utils.ServiceUtils.activityTaskManager
import com.sunshine.freeform.utils.ServiceUtils.inputManager
import com.sunshine.freeform.utils.ServiceUtils.iWindowManager
import kotlinx.android.synthetic.main.view_bar.view.*
import kotlinx.android.synthetic.main.view_bar_flyme.view.*
import kotlinx.android.synthetic.main.view_floating_button.view.*
import kotlinx.android.synthetic.main.view_freeform.view.*
import kotlinx.android.synthetic.main.view_freeform.view.root
import kotlinx.android.synthetic.main.view_freeform_flyme.view.*
import kotlinx.coroutines.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformView(
    override var config: FreeformConfig,
    private val context: Context,
    private var virtualDisplay: VirtualDisplay,
    var screenListener: ScreenListener,
) : FreeformViewAbs(config), View.OnTouchListener, ScreenListener.ScreenStateListener {

    // Getter publik untuk akses displayId dari luar tanpa expose virtualDisplay langsung
    val displayId: Int
        get() = virtualDisplay.display.displayId

    //ViewModel
    private val viewModel = FreeformViewModel(context)

    private val scope = MainScope()

    //默认屏幕，用于获取横竖屏状态
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //界面binding
    private lateinit var binding: ViewFreeformFlymeBinding

    private lateinit var backgroundView: View

    //该小窗是否已经销毁
    var isDestroy = false

    //是否处于隐藏状态，当打开米窗的正在运行小窗界面时，应当隐藏所有小窗
    var isHidden = false

    //小窗中应用的taskId
    private var taskList = ArrayList<Int>()

    //叠加层Params
    private val windowLayoutParams = WindowManager.LayoutParams()

    private val backgroundViewLayoutParams = WindowManager.LayoutParams()

    //物理屏幕方向
    private var screenRotation = defaultDisplay.rotation
    //虚拟屏幕方向，1 竖屏， 0 横屏
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    private val iRotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            if (rotation != screenRotation) {
                screenRotation = rotation
                scope.launch(Dispatchers.Main) {
                    onScreenOrientationChanged()
                }
            }
        }
    }

    //触摸监听
    private val touchListener = TouchListener()
    private val touchListenerPreQ = TouchListenerPreQ()

    //屏幕宽高，不保证大小
    private var realScreenWidth = 0
        get() {
            var tmpWidth = context.resources.displayMetrics.widthPixels
            var tmpHeight = context.resources.displayMetrics.heightPixels

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rect = windowManager.currentWindowMetrics.bounds
                tmpWidth = rect.width()
                tmpHeight = rect.height()
            }
            return if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180)
                        min(tmpWidth, tmpHeight)
                   else
                        max(tmpWidth, tmpHeight)
        }
    private var realScreenHeight = 0
        get() {
            var tmpWidth = context.resources.displayMetrics.widthPixels
            var tmpHeight = context.resources.displayMetrics.heightPixels

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rect = windowManager.currentWindowMetrics.bounds
                tmpWidth = rect.width()
                tmpHeight = rect.height()
            }

            return if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180)
                        max(tmpWidth, tmpHeight)
                   else
                        min(tmpWidth, tmpHeight)
        }

    //小窗的"尺寸"，该尺寸只在小窗内屏幕方向改变时变化
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //小窗界面的宽高，该宽高不随着屏幕、小窗方向改变而改变，即h>w恒成立。该尺寸只在物理屏幕方向变化时变化
    private var freeformHeight = 0
    private var freeformWidth = 0

    private var minFreeformHeight = 0
    private var minFreeformWidth = 0

    private var maxFreeformHeight = 0
    private var maxFreeformWidth = 0

    // 挂起后与边缘的 Padding
    private var screenPaddingX: Int = context.resources.getDimension(R.dimen.freeform_screen_width_padding).roundToInt()
    private var screenPaddingY: Int = context.resources.getDimension(R.dimen.freeform_screen_height_padding).roundToInt()

    // Margins
    private var barHeight: Float = context.resources.getDimension(R.dimen.bottom_bar_height_flyme)
    private var freeformShadow: Float = context.resources.getDimension(R.dimen.freeform_shadow)
    private var cardHeightMargin: Float = 0f
        get() {
            return if (FreeformHelper.screenIsPortrait(screenRotation)) (barHeight + freeformShadow) else 0f
        }
    private var cardWidthMargin: Float = 0f
        get() {
            return if (FreeformHelper.screenIsPortrait(screenRotation)) 0f else barHeight
        }

    // 存储上一次的悬浮位置
    private var lastFloatViewLocation: IntArray = intArrayOf(-1, -1)

    // 小窗大小
    private var hangUpViewHeight = 0
    private var hangUpViewWidth = 0

    // root
    private var rootHeight = 0
        get() {
            var tmp = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenHeight else realScreenWidth
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = ((rootWidth * config.widthHeightRatio) + cardHeightMargin).roundToInt()
                if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                    tmp = realScreenHeight
                }
            }
            return tmp
        }
    private var rootWidth = 0
        get() {
            var tmp = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenWidth else realScreenHeight
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = realScreenWidth
            }
            return tmp
        }

    // 小窗缩放比例
    private var mScaleX = 1f
        set(value) {
            if (value > 1f) return
            field = value
            binding.freeformRoot.scaleX = value
        }
    private var mScaleY = 1f
        set(value) {
            if (value > 1f) return
            field = value
            binding.freeformRoot.scaleY = value
        }

    // 触发互动的比例
    private var goFloatScale = 0.9f
    private var goFullScale = 1.05f

    //缩放比例
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    //新增 手动调整小窗方向 q220904.7
    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (config.manualAdjustFreeformRotation) {
                virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                    VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
                } else {
                    VIRTUAL_DISPLAY_ROTATION_PORTRAIT
                }
                onFreeFormRotationChanged()
            } else {
                // Double tap pada bar bawah → suspend/mini mode
                if (enableSuspendMode) toSuspendMode()
            }
            return false
        }
    })

    private val backgroundGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isFloating) {
                // Hanya close kalau setting tap_outside_to_close aktif
                if (viewModel.getBooleanSp("tap_outside_to_close", false)) {
                    destroy()
                }
            }
            return true
        }
    })

    private val sharedPreferencesChangeListener =
        OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                "freeform_float_view_size" -> {
                    config.floatViewSize = (sharedPreferences.getInt(key, 20)) / 100.toFloat()
                    initFloatViewSize()
                    if (isFloating) {
                        if (isHidden) {
                            hiddenViewToFloatView(false)
                        }

                        binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * (hangUpViewWidth / rootWidth)

                        val windowCoordinate = intArrayOf(
                            windowLayoutParams.x,
                            windowLayoutParams.y,
                        )

                        val location = genFloatViewLocation()
                        lastFloatViewLocation[0] = location[0]

                        AnimatorSet().apply {
                            playTogether(
                                ValueAnimator.ofInt(windowLayoutParams.width, hangUpViewWidth)
                                    .apply {
                                        addUpdateListener {
                                            windowManager.updateViewLayout(
                                                binding.root,
                                                windowLayoutParams.apply {
                                                    width = it.animatedValue as Int
                                                })
                                        }
                                    },
                                ValueAnimator.ofInt(windowLayoutParams.height, hangUpViewHeight)
                                    .apply {
                                        addUpdateListener {
                                            windowManager.updateViewLayout(
                                                binding.root,
                                                windowLayoutParams.apply {
                                                    height = it.animatedValue as Int
                                                })
                                        }
                                    },
                                moveViewAnim(windowCoordinate, lastFloatViewLocation)
                            )
                            duration = 200
                            start()
                        }
                    }
                }
                "window_opacity" -> {
                    windowOpacity = sharedPreferences.getInt(key, 100)
                    if (::binding.isInitialized) {
                        binding.freeformRoot.alpha = windowOpacity / 100f
                    }
                }
                "corner_radius" -> {
                    cornerRadiusValue = sharedPreferences.getInt(key, 0).toFloat()
                    if (::binding.isInitialized) {
                        if (cornerRadiusValue > 0) {
                            binding.cardRoot.radius = cornerRadiusValue
                        } else {
                            binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius)
                        }
                    }
                }
                "lock_window_position" -> {
                    isWindowLocked = sharedPreferences.getBoolean(key, false)
                }
                "auto_close_screen_off" -> {
                    autoCloseScreenOff = sharedPreferences.getBoolean(key, false)
                }
                "auto_minimize_on_call" -> {
                    autoMinimizeOnCall = sharedPreferences.getBoolean(key, false)
                    if (autoMinimizeOnCall) {
                        registerPhoneCallReceiver()
                    } else {
                        unregisterPhoneCallReceiver()
                    }
                }
                "enable_quick_notes" -> {
                    if (sharedPreferences.getBoolean(key, false)) {
                        initQuickNotesOverlay()
                    } else {
                        removeQuickNotesOverlay()
                    }
                }
                "enable_focus_timer" -> {
                    if (sharedPreferences.getBoolean(key, false)) {
                        initFocusTimer()
                    } else {
                        removeFocusTimer()
                    }
                }
                "show_perf_overlay" -> {
                    if (sharedPreferences.getBoolean(key, false)) {
                        initPerfOverlay()
                    } else {
                        removePerfOverlay()
                    }
                }
                "enable_shake_minimize" -> {
                    enableShakeMinimize = sharedPreferences.getBoolean(key, false)
                    if (enableShakeMinimize) {
                        registerShakeListener()
                    } else {
                        unregisterShakeListener()
                    }
                }
                "enable_swipe_back" -> {
                    enableSwipeBack = sharedPreferences.getBoolean(key, true)
                }
                "enable_swipe_home" -> {
                    enableSwipeHome = sharedPreferences.getBoolean(key, false)
                }
                "enable_swipe_forward" -> {
                    enableSwipeForward = sharedPreferences.getBoolean(key, false)
                }
                "enable_pinch_resize" -> {
                    enablePinchResize = sharedPreferences.getBoolean(key, false)
                }
                "remember_freeform_size" -> {
                    rememberFreeformSize = sharedPreferences.getBoolean(key, true)
                }
                "enable_suspend_mode" -> {
                    enableSuspendMode = sharedPreferences.getBoolean(key, true)
                }
                "enable_destroy_anim" -> {
                    enableDestroyAnim = sharedPreferences.getBoolean(key, true)
                }
                "snap_to_edge" -> {
                    // Snap to edge diatur saat move selesai
                }
                "show_top_bar" -> {
                    applyTopBarVisibility()
                }
                else -> {
                    initConfig()
                }
            }
        }

    //是否处于挂起状态
    var isFloating = false
    //挂起位置，0：是否在左，1：是否在上
    private val hangUpPosition = booleanArrayOf(false, true)

    @RequiresApi(Build.VERSION_CODES.Q)
    private val taskStackListener = MTaskStackListener()

    fun initSystemService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityTaskManager.registerTaskStackListener(taskStackListener)
        }
    }

    fun initConfig() {
        initFloatViewSize()

        config.freeformDpi = FreeformHelper.getScreenDpi(context)
        val tmpDpi = viewModel.getIntSp("freeform_scale", 50)
        if (tmpDpi > 50) {
            config.freeformDpi = tmpDpi
        }

        freeformScreenHeight = (min(realScreenHeight, realScreenWidth) / config.widthHeightRatio).roundToInt()
        freeformScreenWidth = (freeformScreenHeight * config.widthHeightRatio).roundToInt()

        config.rememberPosition = viewModel.getBooleanSp("remember_freeform_position", false)
        if (config.rememberPosition) {
            lastFloatViewLocation[0] = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_X, -1)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_X, -1)
            }
            lastFloatViewLocation[1] = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_Y, -1)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_Y, -1)
            }
        }
        config.floatViewSize = (viewModel.getIntSp("freeform_float_view_size", 20)) / 100.toFloat()
        config.freeformSize = (viewModel.getIntSp("freeform_size", 75)) / 100.toFloat()
        config.freeformSizeLand = (viewModel.getIntSp("freeform_size_land", 90)) / 100.toFloat()
        config.dimAmount = (viewModel.getIntSp("freeform_dimming_amount", 20)) / 100.toFloat()

        viewModel.registerOnSharedPreferenceChangeListener(sharedPreferencesChangeListener)

        config.useSuiRefuseToFullScreen = viewModel.getBooleanSp("use_sui_refuse_to_fullscreen", false)
        config.manualAdjustFreeformRotation = viewModel.getBooleanSp("manual_adjust_freeform_rotation", false)

        // Baca setting baru
        enableSwipeBack = viewModel.getBooleanSp("enable_swipe_back", true)
        enableSuspendMode = viewModel.getBooleanSp("enable_suspend_mode", true)
        enableDestroyAnim = viewModel.getBooleanSp("enable_destroy_anim", true)
        rememberFreeformSize = viewModel.getBooleanSp("remember_freeform_size", true)

        // Gesture tambahan
        enableSwipeHome = viewModel.getBooleanSp("enable_swipe_home", false)
        enableSwipeForward = viewModel.getBooleanSp("enable_swipe_forward", false)
        enablePinchResize = viewModel.getBooleanSp("enable_pinch_resize", false)
        enableShakeMinimize = viewModel.getBooleanSp("enable_shake_minimize", false)

        // Tampilan
        windowOpacity = viewModel.getIntSp("window_opacity", 100)
        cornerRadiusValue = viewModel.getIntSp("corner_radius", 0).toFloat()

        // Performa
        autoCloseScreenOff = viewModel.getBooleanSp("auto_close_screen_off", false)
        autoMinimizeOnCall = viewModel.getBooleanSp("auto_minimize_on_call", false)
        isWindowLocked = viewModel.getBooleanSp("lock_window_position", false)
    }

    /**
     * Inisialisasi ukuran float view berdasarkan config.floatViewSize
     */
    private fun initFloatViewSize() {
        hangUpViewHeight = (rootHeight * config.floatViewSize).roundToInt()
        hangUpViewWidth = (hangUpViewHeight * config.widthHeightRatio).roundToInt()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            hangUpViewWidth = (realScreenHeight * config.floatViewSize).roundToInt()
            hangUpViewHeight = (hangUpViewWidth * config.widthHeightRatio).roundToInt()
            if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                hangUpViewWidth = (realScreenWidth * config.floatViewSize).roundToInt()
                hangUpViewHeight = (hangUpViewWidth * config.widthHeightRatio).roundToInt()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView() {
        binding = ViewFreeformFlymeBinding.bind(LayoutInflater.from(context).inflate(R.layout.view_freeform_flyme, null, false))

        backgroundView = View(context)
        backgroundView.setBackgroundColor(Color.TRANSPARENT)
        backgroundView.setOnTouchListener(this@FreeformView)
        backgroundView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                performBackKey()
            }
            true
        }
        backgroundView.id = View.generateViewId()

        binding.root.setOnTouchListener(this)
        binding.bottomBar.middleView.setOnTouchListener(this@FreeformView)
        binding.bottomBar.sideView.setOnTouchListener(this@FreeformView)

        val topBarTouchListener = TopBarTouchListener()
        binding.topBar.root.setOnTouchListener(topBarTouchListener)
        binding.topBar.leftView.setOnTouchListener(topBarTouchListener)
        binding.topBar.middleView.setOnTouchListener(topBarTouchListener)
        binding.topBar.rightView.setOnTouchListener(topBarTouchListener)
        applyTopBarVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            hangUpPosition[0] = true
            binding.apply {
                (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                    topMargin = 0
                    bottomMargin = 0
                    rightMargin = barHeight.roundToInt()
                }
            }
        }

        refreshFreeformSize()

        initFloatBar()

        resetScale()

        // Apply tampilan awal
        if (windowOpacity < 100) {
            binding.freeformRoot.alpha = windowOpacity / 100f
        }

        if (cornerRadiusValue > 0) {
            binding.cardRoot.radius = cornerRadiusValue
        }

        binding.freeformRoot.alpha = 1f
        binding.textureView.alpha = 0f
    }

    private fun applyTopBarVisibility() {
        if (!::binding.isInitialized) return
        val show = viewModel.getBooleanSp("show_top_bar", true)
        binding.topBar.root.visibility = if (show) View.VISIBLE else View.GONE
    }

    private inner class TopBarTouchListener : View.OnTouchListener {
        private var moveStartX = 0f
        private var moveStartY = 0f
        private var isMoved = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    isMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - moveStartX
                    val dy = event.rawY - moveStartY
                    if (!isMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isMoved = true
                    }
                    if (isMoved && !isWindowLocked && !isDestroy) {
                        runCatching {
                            windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                x += dx.toInt()
                                y += dy.toInt()
                            })
                        }
                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        if (viewModel.getBooleanSp("snap_to_edge", true)) {
                            snapToEdge()
                        }
                    } else {
                        when (v.id) {
                            R.id.leftView -> performBackKey()
                            R.id.rightView -> destroyWithAnim()
                        }
                    }
                    isMoved = false
                }
                MotionEvent.ACTION_CANCEL -> isMoved = false
            }
            return true
        }
    }

    private fun performBackKey() {
        val downEvent = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK,
            0
        )
        val upEvent = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_BACK,
            0
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(downEvent, 0)

                setDisplayIdMethod?.invoke(upEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, 0)
            } else {
                inputManager.injectInputEvent(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, virtualDisplay.display.displayId)
            }
        }
    }

    private fun initFloatBar() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    barHeight.roundToInt(),
                ).apply {
                    topToBottom = R.id.cardRoot
                    startToEnd = ConstraintLayout.LayoutParams.UNSET
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.VISIBLE
                sideView.visibility = View.GONE
            }
        } else {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    barHeight.roundToInt(),
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToEnd = R.id.cardRoot
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.GONE
                sideView.visibility = View.VISIBLE
            }
        }
    }

    private fun initDisplay() {
        virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, config.freeformDpi)
        screenListener.addScreenStateListener(this@FreeformView)
    }

    override fun onScreenOn() {
    }

    override fun onScreenOff() {
        if (autoCloseScreenOff) {
            scope.launch(Dispatchers.Main) { destroyWithAnim() }
            return
        }
        if (!isHidden) {
            windowLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    override fun onUserPresent() {
        if (!isHidden) {
            windowLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM

            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun initOrientationChangedListener() {
        iWindowManager.watchRotation(iRotationWatcher, Display.DEFAULT_DISPLAY)
    }

    private fun initTextureViewListener() {
        var updateFrameCount = 0
        var initFinish = false

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
                virtualDisplay.surface = Surface(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!initFinish) {
                    ++updateFrameCount
                    if (updateFrameCount > 2) {
                        binding.lottieView.cancelAnimation()
                        binding.lottieView.animate().alpha(0f).setDuration(200).start()
                        binding.textureView.animate().alpha(1f).setDuration(200).start()
                        initFinish = true
                    }
                }
            }
        }
    }

    fun showWindow() {
        initDisplay()
        initOrientationChangedListener()
        initTextureViewListener()

        // Apply ke view sebelum ditampilkan
        if (windowOpacity < 100) {
            binding.freeformRoot.alpha = windowOpacity / 100f
        }

        if (cornerRadiusValue > 0) {
            binding.cardRoot.radius = cornerRadiusValue
        }

        // Setup overlay tambahan
        if (viewModel.getBooleanSp("enable_quick_notes", false)) {
            initQuickNotesOverlay()
        }

        if (viewModel.getBooleanSp("enable_focus_timer", false)) {
            initFocusTimer()
        }

        initPerfOverlay()

        // Setup shake sensor
        if (enableShakeMinimize) {
            registerShakeListener()
        }

        // Setup auto minimize on call
        if (autoMinimizeOnCall) {
            registerPhoneCallReceiver()
        }

        // Baca setting tap outside to close
        val tapOutsideToClose = viewModel.getBooleanSp("tap_outside_to_close", false)

        windowLayoutParams.apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                        if (tapOutsideToClose) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                             WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }

        setWindowNoUpdateAnimation()

        windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
        }

        if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
        }

        backgroundViewLayoutParams.apply {
            dimAmount = config.dimAmount
            format = PixelFormat.RGBA_8888
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            flags = windowLayoutParams.flags or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        runCatching {
            windowManager.addView(backgroundView, backgroundViewLayoutParams)
            windowManager.addView(binding.root, windowLayoutParams)
        }.onFailure {
            runCatching {
                windowManager.removeViewImmediate(backgroundView)
                windowManager.removeViewImmediate(binding.root)
            }

            if (Settings.canDrawOverlays(context)) {
                windowManager.addView(backgroundView, backgroundViewLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
                windowManager.addView(binding.root, windowLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
            } else {
                destroy()
                runCatching {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setWindowNoUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue or noAnimFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setWindowEnableUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue and noAnimFlag.inv()
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun onFreeFormRotationChanged() {
        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        initFloatViewSize()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            freeformScreenHeight = tempHeight
            freeformScreenWidth = tempWidth
        } else {
            freeformScreenHeight = tempWidth
            freeformScreenWidth = tempHeight
        }
        refreshFreeformSize()
        resetScale()
        resizeVirtualDisplay()
        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
            x = genCenterLocation()[0]
            y = genCenterLocation()[1]
        })
    }

    private fun onScreenOrientationChanged() {
        initFloatViewSize()

        refreshFreeformSize()

        // Restore ukuran yang disimpan per orientasi
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            if (savedWidthPortrait > 0) {
                freeformWidth = savedWidthPortrait
                freeformHeight = savedHeightPortrait
            }
        } else {
            if (savedWidthLandscape > 0) {
                freeformWidth = savedWidthLandscape
                freeformHeight = savedHeightLandscape
            }
        }

        initFloatBar()

        val location = genFloatViewLocation()
        lastFloatViewLocation = location

        refreshTouchScale()
        refreshActionScale()

        if (isFloating && !isHidden) {
            moveFloatViewLocation(location, true)
        } else if (isHidden) {
            moveHiddenViewLocation(location)
        } else {
            windowLayoutParams.apply {
                height = rootHeight
                width = rootWidth
            }
            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                topMargin = freeformShadow.roundToInt()
                bottomMargin = barHeight.roundToInt()
                rightMargin = 0
            }
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
            if(!FreeformHelper.screenIsPortrait(screenRotation)) {
                binding.apply {
                    (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                        topMargin = 0
                        bottomMargin = 0
                        rightMargin = barHeight.roundToInt()
                    }
                }
            }
            resetScale()
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun genCenterLocation(): IntArray {
        val center = intArrayOf(0, 0)
        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            center[0] = (freeformWidth - rootHeight + screenPaddingX) / 2
            if (!hangUpPosition[0])
                center[0] = (freeformWidth - rootHeight + screenPaddingX) / -2
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / 2
                if (!hangUpPosition[0])
                    center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / -2
            }
        }
        return center
    }

    private fun resizeVirtualDisplay() {
        virtualDisplay.resize(
            freeformScreenWidth,
            freeformScreenHeight,
            config.freeformDpi
        )
    }

    override fun toScreenCenter() {
        if (isFloating) return
        windowLayoutParams.x = 0
        windowLayoutParams.y = 0
    }

    override fun moveToFirst() {
        if (isFloating) {
            if (isHidden) {
                hiddenViewToFloatView(true)
            } else {
                floatViewToMiniView()
            }
        }
    }

    private fun refreshFreeformSize() {
        freeformHeight = if (FreeformHelper.screenIsPortrait(screenRotation)) (rootWidth / config.widthHeightRatio * config.freeformSize).roundToInt() else (rootWidth * config.freeformSizeLand).roundToInt()
        freeformHeight += cardHeightMargin.roundToInt()
        freeformWidth = ((freeformHeight + cardWidthMargin) * config.widthHeightRatio).roundToInt()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            if (freeformHeight > rootWidth) {
                freeformWidth = (rootWidth - (rootWidth * 0.05)).roundToInt()
                freeformHeight = ((freeformWidth + cardHeightMargin) * config.widthHeightRatio) .roundToInt()
            }
            if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                freeformWidth = (realScreenWidth / 2 + cardWidthMargin).roundToInt()
                freeformHeight = (freeformWidth * config.widthHeightRatio).roundToInt()
            }
        }

        minFreeformHeight = (freeformHeight * 0.6f).roundToInt()
        minFreeformWidth = (freeformWidth * 0.6f).roundToInt()
        maxFreeformHeight = (rootHeight * 0.95f).roundToInt()
        maxFreeformWidth = (rootWidth * 0.95f).roundToInt()
    }

    private fun refreshScale() {
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()
    }

    private fun refreshTouchScale() {
        scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
        scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
    }

    private fun refreshActionScale() {
        goFloatScale = (freeformHeight * 0.9f) / rootHeight
        goFullScale = (freeformHeight * 1.05f) / rootHeight
    }

    private fun resetScale() {
        refreshTouchScale()
        refreshScale()
        refreshActionScale()
    }

    private var lastX = -1f
    private var lastY = -1f
    private var touchId = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> handleDownEvent(v, event)
            MotionEvent.ACTION_MOVE -> handleMoveEvent(v, event)
            MotionEvent.ACTION_UP -> handleUpEvent(v, event)
        }
        return true
    }

    private fun handleDownEvent(v: View, event: MotionEvent) {
        if (touchId == -1) touchId = v.id
        lastX = event.rawX
        lastY = event.rawY
        when(v.id) {
            R.id.root, backgroundView.id -> backgroundGestureDetector.onTouchEvent(event)
            R.id.middleView -> middleGestureDetector.onTouchEvent(event)
            R.id.sideView -> middleGestureDetector.onTouchEvent(event)
        }
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when(v.id) {
            R.id.root, backgroundView.id -> backgroundGestureDetector.onTouchEvent(event)
            R.id.middleView -> {
                if (touchId == R.id.middleView) {
                    val dy = event.rawY - lastY
                    handleToFloatScale(0f, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                    middleGestureDetector.onTouchEvent(event)
                }
            }
            R.id.sideView -> {
                if (touchId == R.id.sideView) {
                    val dx = event.rawX - lastX
                    handleToFloatScale(dx, 0f)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
        }
    }

    private fun handleUpEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.root, backgroundView.id -> backgroundGestureDetector.onTouchEvent(event)
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
                notifyToFloat()
                if (isZoomOut) {
                    // Update virtualDisplay sesuai ukuran baru
                    freeformScreenWidth = (freeformWidth - cardWidthMargin).roundToInt()
                    freeformScreenHeight = (freeformHeight - cardHeightMargin).roundToInt()
                    resizeVirtualDisplay()
                    scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
                    scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
                    // Simpan ukuran untuk remember size
                    if (rememberFreeformSize) {
                        if (FreeformHelper.screenIsPortrait(screenRotation)) {
                            savedWidthPortrait = freeformWidth
                            savedHeightPortrait = freeformHeight
                        } else {
                            savedWidthLandscape = freeformWidth
                            savedHeightLandscape = freeformHeight
                        }
                    }
                    isZoomOut = false
                }
            }
            R.id.sideView -> {
                notifyToFloat()
                middleGestureDetector.onTouchEvent(event)
                if (isZoomOut) {
                    freeformScreenWidth = (freeformWidth - cardWidthMargin).roundToInt()
                    freeformScreenHeight = (freeformHeight - cardHeightMargin).roundToInt()
                    resizeVirtualDisplay()
                    scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
                    scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
                    if (rememberFreeformSize) {
                        if (FreeformHelper.screenIsPortrait(screenRotation)) {
                            savedWidthPortrait = freeformWidth
                            savedHeightPortrait = freeformHeight
                        } else {
                            savedWidthLandscape = freeformWidth
                            savedHeightLandscape = freeformHeight
                        }
                    }
                    isZoomOut = false
                }
            }
        }
        touchId = -1
    }

    private var setDisplayIdMethod: Method? = null

    private fun genFloatViewLocation(): IntArray {
        return intArrayOf(
            (if (hangUpPosition[0]) ((realScreenWidth - hangUpViewWidth - screenPaddingX) / -2)
                else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2),
            (if (hangUpPosition[1]) (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                else (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2),
        )
    }

    private fun getRestoreFreeformScale(): FloatArray {
        refreshFreeformSize()
        return floatArrayOf(
            freeformWidth / rootWidth.toFloat(),
            freeformHeight / rootHeight.toFloat(),
        )
    }

    private fun cardViewMarginAnim(topStartMargin: Int, bottomStartMargin: Int, rightStartMargin: Int, topEndMargin: Int, bottomEndMargin: Int, rightEndMargin: Int): Animator {
        return AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(topStartMargin, topEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            topMargin = it.animatedValue as Int
                        }
                    }
                },
                ValueAnimator.ofInt(bottomStartMargin, bottomEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            bottomMargin = it.animatedValue as Int
                        }
                    }
                },
                ValueAnimator.ofInt(rightStartMargin, rightEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            rightMargin = it.animatedValue as Int
                        }
                    }
                },
            )
        }
    }

    private fun moveViewAnim(startCoordinate: IntArray, endCoordinate: IntArray): Animator {
        val moveAnim = AnimatorSet()
        if (endCoordinate[0] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[0], endCoordinate[0]).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            x = it.animatedValue as Int
                        })
                    }
                },
            )
        }
        if (endCoordinate[1] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[1], endCoordinate[1]).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            y = it.animatedValue as Int
                        })
                    }
                },
            )
        }
        return moveAnim
    }

    private var isZoomOut = false

    private fun handleToFloatScale(dx: Float, dy: Float) {
        if (isFloating) return

        if (dy != 0f) {
            val tempHeight = freeformHeight + dy
            val tempWidth = (tempHeight * config.widthHeightRatio).roundToInt()
            if (tempHeight >= minFreeformHeight && tempWidth <= maxFreeformWidth) {
                freeformHeight += dy.roundToInt()
                freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
                if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                    freeformWidth = ((freeformHeight - (cardHeightMargin * config.widthHeightRatio)) / config.widthHeightRatio).roundToInt()
                }
                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                isZoomOut = true
            }
        } else if (dx != 0f) {
            val tempWidth = freeformWidth + dx
            val tempHeight = (tempWidth / config.widthHeightRatio).roundToInt()
            if (tempWidth >= minFreeformWidth && tempHeight <= maxFreeformHeight) {
                freeformWidth += dx.roundToInt()
                freeformHeight = ((freeformWidth / config.widthHeightRatio) - cardWidthMargin).roundToInt()
                if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                    freeformHeight = ((freeformWidth + cardHeightMargin) * config.widthHeightRatio).roundToInt()
                }
                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                isZoomOut = true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun notifyToFloat() {
        if (isZoomOut) {
            val scaleX: Float = hangUpViewWidth / rootWidth.toFloat()
            val scaleY: Float = hangUpViewHeight / rootHeight.toFloat()

            if (mScaleY <= goFloatScale) {
                val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
                var location = genFloatViewLocation()
                if (lastFloatViewLocation[0] != -1) location = lastFloatViewLocation

                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, scaleX),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, scaleY),
                        ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0, 0, 0,
                        ),
                        moveViewAnim(windowCoordinate, location),
                    )
                    addListener(
                        onStart = {
                            AnimatorSet().apply {
                                playTogether(
                                    ValueAnimator.ofFloat(config.dimAmount, 0f).apply {
                                        addUpdateListener {
                                            windowManager.updateViewLayout(backgroundView, backgroundViewLayoutParams.apply {
                                                dimAmount = it.animatedValue as Float
                                            })
                                        }
                                    },
                                )
                                startDelay = 125
                                duration = 600
                                addListener(
                                    onStart = {
                                        backgroundView.visibility = View.GONE
                                        binding.textureView.setOnTouchListener(null)
                                        isFloating = true
                                    },
                                    onEnd = {
                                        binding.textureView.setOnTouchListener(FloatViewTouchListener())
                                        setWindowEnableUpdateAnimation()
                                    },
                                )
                                start()
                            }
                        },
                        onEnd = {
                            mScaleX = scaleX
                            mScaleY = scaleY
                            binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * scaleX
                            windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                height = (rootHeight * scaleY).roundToInt()
                                width = (rootWidth * scaleX).roundToInt()
                            })
                            binding.freeformRoot.scaleY = 1f
                            binding.freeformRoot.scaleX = 1f
                        }
                    )
                    duration = 400
                    start()
                }
            } else if (mScaleY >= goFullScale) {
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, 1f),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, 1f),
                        ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0, 0, 0,
                        ),
                    )
                    addListener(
                        onEnd = {
                            context.startService(
                                Intent(context, FreeformService::class.java)
                                    .setAction(FreeformService.ACTION_CALL_INTENT)
                                    .putExtra(Intent.EXTRA_INTENT, config.intent)
                                    .putExtra(FreeformService.EXTRA_DISPLAY_ID, defaultDisplay.displayId)
                            )
                            destroy()
                        }
                    )
                    duration = 400
                    start()
                }
            } else {
                val restoreScale = getRestoreFreeformScale()
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScale[0]),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScale[1]),
                    )
                    duration = 300
                    interpolator = OvershootInterpolator(1.5f)
                    start()
                }
            }
            isZoomOut = false
        }
    }

    private fun moveFloatViewLocation(location: IntArray, reset: Boolean) {
        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(
                onStart = {
                    if (reset) {
                        binding.freeformRoot.scaleY = 1f
                        binding.freeformRoot.scaleX = 1f
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            height = hangUpViewHeight
                            width = hangUpViewWidth
                        })
                    }
                }
            )
            duration = 600
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private fun moveHiddenViewLocation(location: IntArray) {
        val layoutParams = hiddenView.layoutParams as WindowManager.LayoutParams
        val windowCoordinate = intArrayOf(layoutParams.x, layoutParams.y)

        var position = 0
        if (layoutParams.x > 0) {
            location[0] += (hangUpViewWidth + screenPaddingX)
            position = 1
        } else {
            location[0] -= (hangUpViewWidth + screenPaddingX)
            position = -1
        }

        val floatingButtonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()

        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(windowCoordinate[0], (realScreenWidth - floatingButtonWidth) / 2 * position).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(hiddenView, layoutParams.apply { x = it.animatedValue as Int })
                    }
                },
                ValueAnimator.ofInt(windowCoordinate[1], location[1]).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(hiddenView, layoutParams.apply { y = it.animatedValue as Int })
                    }
                },
                moveViewAnim(
                    intArrayOf(windowLayoutParams.x, windowLayoutParams.y),
                    intArrayOf(location[0], location[1])
                )
            )
            duration = 600
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private lateinit var hiddenView: View

    private inner class FloatViewTouchListener : View.OnTouchListener {
        var moveStartX: Float = -1f
        var moveStartY: Float = -1f
        var movedX: Float = -1f
        var movedY: Float = -1f
        var isMoved: Boolean = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            if (v?.id == R.id.root) {
                hideGestureDetector.onTouchEvent(event)
                return true
            }
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    hangUpGestureDetector.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isWindowLocked) {
                        movedX = event.rawX - moveStartX
                        movedY = event.rawY - moveStartY
                        isMoved = true
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            x += movedX.toInt()
                            y += movedY.toInt()
                        })
                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        val nowX = event.rawX
                        val nowY = event.rawY
                        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
                        // Snap to edge
                        if (viewModel.getBooleanSp("snap_to_edge", true)) {
                            snapToEdge()
                        }

                        if (windowCoordinate[1] >= (realScreenHeight - screenPaddingY) / 2) {
                            destroy()
                            isMoved = false
                            return true
                        }

                        hangUpPosition[0] = windowCoordinate[0] <= 0
                        hangUpPosition[1] = windowCoordinate[1] <= 0

                        val location = genFloatViewLocation()
                        location[1] = windowLayoutParams.y

                        if (nowY < (realScreenHeight * 0.1f)) {
                            location[1] = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                        }
                        if (nowY > (realScreenHeight - (realScreenHeight * 0.1f))) {
                            location[1] = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                        }

                        var position = 0
                        if (windowCoordinate[0] <= (realScreenWidth - (screenPaddingX / 2)) / -2) {
                            location[0] -= (hangUpViewWidth + screenPaddingX)
                            position = -1
                        } else if (windowCoordinate[0] >= (realScreenWidth - (screenPaddingX / 2)) / 2) {
                            location[0] += (hangUpViewWidth + screenPaddingX)
                            position = 1
                        }

                        AnimatorSet().apply {
                            playTogether(moveViewAnim(windowCoordinate, location))
                            addListener(
                                onStart = {
                                    if (position != 0) {
                                        isHidden = true
                                        hiddenView = LayoutInflater.from(context).inflate(R.layout.view_floating_button, null, false)
                                        hiddenView.root.apply { setOnTouchListener(this@FloatViewTouchListener) }
                                        if (position == 1)
                                            hiddenView.backgroundView.background = context.getDrawable(R.drawable.floating_button_bg_right)

                                        val floatingButtonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()
                                        val floatingButtonHeight = context.resources.getDimension(R.dimen.floating_button_height).toInt()

                                        windowManager.addView(hiddenView, WindowManager.LayoutParams().apply {
                                            x = (realScreenWidth - floatingButtonWidth) / 2 * position
                                            y = location[1]
                                            width = floatingButtonWidth
                                            height = floatingButtonHeight
                                            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                            format = PixelFormat.TRANSLUCENT
                                            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                        })
                                    }
                                },
                                onEnd = {
                                    if (!isHidden) lastFloatViewLocation = location
                                    isMoved = false
                                }
                            )
                            duration = 400
                            interpolator = OvershootInterpolator(2f)
                            start()
                        }
                    } else {
                        hangUpGestureDetector.onTouchEvent(event)
                    }
                }
            }
            return true
        }
    }

    private val hangUpGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        @SuppressLint("ClickableViewAccessibility")
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            floatViewToMiniView()
            return true
        }
        override fun onLongPress(e: MotionEvent) {}
    })

    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToMiniView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        val restoreScale = getRestoreFreeformScale()
        val center: IntArray = genCenterLocation()

        setWindowNoUpdateAnimation()

        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(backgroundView, backgroundViewLayoutParams.apply {
                            dimAmount = it.animatedValue as Float
                        })
                    }
                },
            )
            addListener(
                onStart = {
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        height = rootHeight
                        width = rootWidth
                    })
                    binding.freeformRoot.scaleX = mScaleX
                    binding.freeformRoot.scaleY = mScaleY
                    binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius)

                    var topMargin = 0f
                    var bottomMargin = 0f
                    if (FreeformHelper.screenIsPortrait(screenRotation)) {
                        topMargin = freeformShadow
                        bottomMargin = barHeight
                    }

                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 1f),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScale[0]),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScale[1]),
                            cardViewMarginAnim(
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                                topMargin.roundToInt(),
                                bottomMargin.roundToInt(),
                                cardWidthMargin.roundToInt(),
                            ),
                            moveViewAnim(windowCoordinate, center),
                        )
                        duration = 400
                        startDelay = 150
                        start()
                    }
                },
                onEnd = { backgroundView.visibility = View.VISIBLE }
            )
            duration = 400
            start()
        }

        isFloating = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToFloatView(goMiniView: Boolean) {
        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        hangUpPosition[0] = windowCoordinate[0] <= 0
        hangUpPosition[1] = windowCoordinate[1] <= 0

        val location: IntArray = intArrayOf(
            (if (hangUpPosition[0]) ((realScreenWidth - hangUpViewWidth - screenPaddingX) / -2)
            else ((realScreenWidth - hangUpViewWidth - screenPaddingX) / 2)),
            -1,
        )

        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(
                onStart = {
                    hiddenView.root.setOnTouchListener(null)
                    windowManager.removeView(hiddenView)
                    isHidden = false
                },
                onEnd = {
                    if (!isHidden) {
                        lastFloatViewLocation = intArrayOf(location[0], windowCoordinate[1])
                    }
                    if (goMiniView) floatViewToMiniView()
                }
            )
            duration = 400
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private val hideGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hiddenViewToFloatView(false)
            return true
        }
    })

    // ---- Fitur dari eswd04 ----

    // Setting baru dari preferences
    private var enableSwipeBack = true
    private var enableSuspendMode = true
    private var enableDestroyAnim = true
    private var rememberFreeformSize = true

    // Gesture tambahan
    private var enableSwipeHome = false
    private var enableSwipeForward = false
    private var enablePinchResize = false
    private var enableShakeMinimize = false

    // Tampilan
    private var windowOpacity = 100
    private var cornerRadiusValue = 0f

    // Performa
    private var autoCloseScreenOff = false
    private var autoMinimizeOnCall = false
    private var isWindowLocked = false

    // Pinch to resize
    private var pinchStartDistance = 0f
    private var pinchStartWidth = 0
    private var pinchStartHeight = 0
    private var isPinching = false

    // Shake to minimize
    private var sensorManager: android.hardware.SensorManager? = null
    private var accelerometer: android.hardware.Sensor? = null
    private var lastShakeTime = 0L
    private val SHAKE_THRESHOLD = 12f
    private val SHAKE_INTERVAL = 1000L

    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (!enableShakeMinimize) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = kotlin.math.sqrt((x*x + y*y + z*z).toDouble()).toFloat() - android.hardware.SensorManager.GRAVITY_EARTH
            if (acceleration > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > SHAKE_INTERVAL) {
                    lastShakeTime = now
                    scope.launch(Dispatchers.Main) {
                        if (!isFloating) floatViewToMiniView()
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor, accuracy: Int) {}
    }

    private fun registerShakeListener() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(shakeListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterShakeListener() {
        runCatching { sensorManager?.unregisterListener(shakeListener) }
        sensorManager = null
    }

    // Phone call receiver untuk auto minimize
    private var phoneCallReceiver: android.content.BroadcastReceiver? = null
    // Untuk Android 12+
    private var telephonyCallback: android.telephony.TelephonyCallback? = null

    private fun registerPhoneCallReceiver() {
        unregisterPhoneCallReceiver() // Cleanup dulu

        // q-fix: broadcast maupun callback sama-sama butuh READ_PHONE_STATE.
        // Tanpa permission ini fitur auto minimize on call tidak akan pernah berfungsi.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_PHONE_STATE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ - pakai TelephonyCallback
            registerPhoneStateCallback()
        } else {
            // Android < 12 - pakai BroadcastReceiver
            registerPhoneStateReceiver()
        }
    }

    // Untuk Android < 12
    private fun registerPhoneStateReceiver() {
        phoneCallReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
                handlePhoneState(state)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            priority = android.content.IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        runCatching { context.registerReceiver(phoneCallReceiver, filter) }
    }

    // Untuk Android 12+
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerPhoneStateCallback() {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val callback = object : android.telephony.TelephonyCallback(),
            android.telephony.TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val stateStr = when (state) {
                    android.telephony.TelephonyManager.CALL_STATE_RINGING ->
                        android.telephony.TelephonyManager.EXTRA_STATE_RINGING
                    android.telephony.TelephonyManager.CALL_STATE_OFFHOOK ->
                        android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK
                    android.telephony.TelephonyManager.CALL_STATE_IDLE ->
                        android.telephony.TelephonyManager.EXTRA_STATE_IDLE
                    else -> null
                }
                stateStr?.let { handlePhoneState(it) }
            }
        }
        telephonyCallback = callback
        runCatching {
            telephonyManager.registerTelephonyCallback(
                context.mainExecutor,
                callback
            )
        }
    }

    // Handler yang sama untuk keduanya
    private fun handlePhoneState(state: String?) {
        when (state) {
            android.telephony.TelephonyManager.EXTRA_STATE_RINGING,
            android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                scope.launch(Dispatchers.Main) {
                    if (!isFloating && !isDestroy) floatViewToMiniView()
                }
            }
            android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                // Telepon selesai → restore floating window
                scope.launch(Dispatchers.Main) {
                    if (isFloating && !isDestroy) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (isFloating && !isDestroy) moveToFirst()
                        }, 1000)
                    }
                }
            }
        }
    }

    private fun unregisterPhoneCallReceiver() {
        // Unregister BroadcastReceiver (Android < 12)
        runCatching { phoneCallReceiver?.let { context.unregisterReceiver(it) } }
        phoneCallReceiver = null

        // Unregister TelephonyCallback (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                telephonyCallback?.let {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            }
            telephonyCallback = null
        }
    }

    // Remember size per orientasi
    private var savedWidthPortrait = -1
    private var savedHeightPortrait = -1
    private var savedWidthLandscape = -1
    private var savedHeightLandscape = -1

    // Suspend/mini mode
    private var isSuspend = false
    private var suspendTempWidth = -1
    private var suspendTempHeight = -1
    private val SUSPEND_HEIGHT = 384 // 192 * 2
    private val SUSPEND_DISTANCE = 50

    // Simpan ukuran sebelum suspend
    private fun saveSizeBeforeSuspend() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            savedWidthPortrait = freeformWidth
            savedHeightPortrait = freeformHeight
        } else {
            savedWidthLandscape = freeformWidth
            savedHeightLandscape = freeformHeight
        }
        suspendTempWidth = freeformWidth
        suspendTempHeight = freeformHeight
    }

    // Restore ukuran setelah suspend
    private fun restoreSizeAfterSuspend() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            if (savedWidthPortrait > 0) {
                freeformWidth = savedWidthPortrait
                freeformHeight = savedHeightPortrait
            }
        } else {
            if (savedWidthLandscape > 0) {
                freeformWidth = savedWidthLandscape
                freeformHeight = savedHeightLandscape
            }
        }
    }

    // Suspend ke pojok kanan atas
    private fun toSuspendMode() {
        if (isSuspend) {
            // Sudah suspend → restore
            isSuspend = false
            restoreSizeAfterSuspend()
            mScaleX = freeformWidth / rootWidth.toFloat()
            mScaleY = freeformHeight / rootHeight.toFloat()
            windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                width = rootWidth
                height = rootHeight
            })
            return
        }
        isSuspend = true
        saveSizeBeforeSuspend()

        val isLandscape = freeformWidth > freeformHeight
        val suspendW: Int
        val suspendH: Int
        if (isLandscape) {
            suspendW = SUSPEND_HEIGHT
            suspendH = (suspendW * 9 / 16)
        } else {
            suspendH = SUSPEND_HEIGHT
            suspendW = (suspendH * 9 / 16)
        }

        freeformWidth = suspendW
        freeformHeight = suspendH
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()

        // Geser ke pojok kanan atas
        val targetX = (realScreenWidth - suspendW) / 2 - SUSPEND_DISTANCE
        val targetY = (suspendH - realScreenHeight) / 2 + SUSPEND_DISTANCE

        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
            x = targetX
            y = targetY
        })
    }

    // Destroy dengan animasi fade out
    fun destroyWithAnim() {
        if (isDestroy) return
        if (!enableDestroyAnim) {
            destroy()
            return
        }
        scope.launch(Dispatchers.Main) {
            binding.root.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction { destroy() }
                .start()
        }
    }

    override fun destroy() {
        //q-fix: destroy bisa dipanggil berkali-kali (screen off + tap luar + service onDestroy)
        if (isDestroy) return

        if (viewModel.getBooleanSp("remember_freeform_position", false)) {
            val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                sp.edit().putInt(REMEMBER_LAND_X, lastFloatViewLocation[0]).putInt(REMEMBER_LAND_Y, lastFloatViewLocation[1]).apply()
            } else {
                sp.edit().putInt(REMEMBER_X, lastFloatViewLocation[0]).putInt(REMEMBER_Y, lastFloatViewLocation[1]).apply()
            }
        }

        if (isHidden) windowManager.removeView(hiddenView)
        if (isFloating) {
            windowLayoutParams.x = 0
            windowLayoutParams.y = 0
        }

        isDestroy = true
        isHidden = false
        isFloating = false
        pendingTaskDisplayJob?.cancel()
        pendingTaskDisplayJob = null
        swipeIndicatorView?.let { runCatching { windowManager.removeView(it) } }
        swipeIndicatorView = null

        // Cleanup sensor shake
        unregisterShakeListener()

        // Cleanup phone call receiver
        unregisterPhoneCallReceiver()

        // Cleanup quick notes
        removeQuickNotesOverlay()

        // Cleanup focus timer
        removeFocusTimer()

        // Cleanup perf overlay
        removePerfOverlay()

        runCatching {
            windowManager.removeViewImmediate(binding.root)
            windowManager.removeViewImmediate(backgroundView)
        }
        if (virtualDisplay.surface != null) {
            virtualDisplay.surface.release()
            virtualDisplay.surface = null
        }

        //q-fix: VirtualDisplay harus dilepas agar tidak bocor di SystemServer
        runCatching { virtualDisplay.release() }

        runCatching { iWindowManager.removeRotationWatcher(iRotationWatcher) }

        screenListener.removeScreenStateListener(this@FreeformView)
        viewModel.unregisterOnSharedPreferenceChangeListener(sharedPreferencesChangeListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityTaskManager.unregisterTaskStackListener(taskStackListener)
        }
    }

     // Anti-spam untuk onTaskDisplayChanged
    private val TASK_DISPLAY_DEBOUNCE_MS = 1500L
    private var pendingTaskDisplayJob: kotlinx.coroutines.Job? = null

    // Swipe back gesture dengan visual indicator
    private var swipeBackStartX = 0f
    private var swipeBackStartY = 0f
    private var isSwipeBackTracking = false
    private var swipeIndicatorView: android.widget.ImageView? = null
    private val SWIPE_BACK_EDGE_WIDTH = 60f
    private val SWIPE_BACK_MIN_DISTANCE = 100f
    private val SWIPE_BACK_MAX_VERTICAL = 80f

    private fun showSwipeIndicator(fromLeft: Boolean) {
        if (swipeIndicatorView != null) return
        val iv = android.widget.ImageView(context)

        // Garis vertikal hitam seperti sistem Android
        val barWidth = (4 * context.resources.displayMetrics.density).toInt()
        val barHeight = (48 * context.resources.displayMetrics.density).toInt()

        val bg = android.graphics.drawable.GradientDrawable()
        bg.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        bg.cornerRadius = barWidth / 2f

        // Baca transparansi dari setting (0-100), default 80
        val alpha = viewModel.getIntSp("swipe_back_indicator_alpha", 80)
        val alphaInt = (alpha * 2.55f).toInt().coerceIn(0, 255)
        bg.setColor(android.graphics.Color.argb(alphaInt, 0, 0, 0))
        iv.background = bg
        iv.alpha = 0f

        val size = barWidth
        val lp = WindowManager.LayoutParams().apply {
            width = size + (16 * context.resources.displayMetrics.density).toInt()
            height = barHeight
            type = if (Settings.canDrawOverlays(context))
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            x = if (fromLeft) windowLayoutParams.x - (size / 2) else windowLayoutParams.x + windowLayoutParams.width - (size / 2)
            y = windowLayoutParams.y
        }
        runCatching {
            windowManager.addView(iv, lp)
            iv.animate().alpha(1f).setDuration(150).start()
            swipeIndicatorView = iv
        }
    }

    private fun updateSwipeIndicator(progress: Float) {
        swipeIndicatorView?.let { iv ->
            val lp = iv.layoutParams as WindowManager.LayoutParams
            lp.x = (windowLayoutParams.x - (40 * context.resources.displayMetrics.density) + 
                    (progress * 30 * context.resources.displayMetrics.density)).toInt()
            runCatching { windowManager.updateViewLayout(iv, lp) }
            iv.scaleX = 0.8f + (progress * 0.4f)
            iv.scaleY = 0.8f + (progress * 0.4f)
        }
    }

    private fun hideSwipeIndicator(triggered: Boolean) {
        swipeIndicatorView?.let { iv ->
            iv.animate()
                .alpha(0f)
                .scaleX(if (triggered) 1.5f else 0.5f)
                .scaleY(if (triggered) 1.5f else 0.5f)
                .setDuration(200)
                .withEndAction {
                    runCatching { windowManager.removeView(iv) }
                    swipeIndicatorView = null
                }
                .start()
        }
    }

    private fun handleSwipeBackGesture(event: MotionEvent): Boolean {
        if (!enableSwipeBack) return false
        val edgeWidth = SWIPE_BACK_EDGE_WIDTH * context.resources.displayMetrics.density
        val minDistance = SWIPE_BACK_MIN_DISTANCE * context.resources.displayMetrics.density
        val maxVertical = SWIPE_BACK_MAX_VERTICAL * context.resources.displayMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x <= edgeWidth) {
                    swipeBackStartX = event.x
                    swipeBackStartY = event.y
                    isSwipeBackTracking = true
                    showSwipeIndicator(fromLeft = true)
                } else {
                    isSwipeBackTracking = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSwipeBackTracking) {
                    val dx = event.x - swipeBackStartX
                    val progress = (dx / minDistance).coerceIn(0f, 1f)
                    updateSwipeIndicator(progress)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeBackTracking) {
                    val dx = event.x - swipeBackStartX
                    val dy = kotlin.math.abs(event.y - swipeBackStartY)
                    if (dx >= minDistance && dy <= maxVertical) {
                        isSwipeBackTracking = false
                        hideSwipeIndicator(triggered = true)
                        performBackKey()
                        return true
                    }
                    hideSwipeIndicator(triggered = false)
                }
                isSwipeBackTracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                hideSwipeIndicator(triggered = false)
                isSwipeBackTracking = false
            }
        }
        return false
    }

    // ===== QUICK NOTES =====
    private var quickNotesView: View? = null

    private fun initQuickNotesOverlay() {
        removeQuickNotesOverlay()
        val editText = android.widget.EditText(context).apply {
            hint = "Quick notes..."
            setBackgroundColor(0xEE1A1A1A.toInt())
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            setPadding(16, 16, 16, 16)
            textSize = 12f
        }
        quickNotesView = editText
        val lp = WindowManager.LayoutParams().apply {
            width = (200 * context.resources.displayMetrics.density).toInt()
            height = (120 * context.resources.displayMetrics.density).toInt()
            type = if (Settings.canDrawOverlays(context))
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            x = windowLayoutParams.x + windowLayoutParams.width + 10
            y = windowLayoutParams.y
        }
        runCatching { windowManager.addView(quickNotesView, lp) }
    }

    private fun removeQuickNotesOverlay() {
        runCatching { quickNotesView?.let { windowManager.removeView(it) } }
        quickNotesView = null
    }

    // ===== FOCUS TIMER =====
    private var focusTimerView: android.widget.TextView? = null
    private var focusTimerJob: kotlinx.coroutines.Job? = null
    private var focusTimeSeconds = 25 * 60
    private var isFocusTimerRunning = false

    private fun initFocusTimer() {
        removeFocusTimer()
        val tv = android.widget.TextView(context).apply {
            text = "25:00"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setBackgroundColor(0xCC000000.toInt())
            setPadding(16, 8, 16, 8)
            setOnClickListener { toggleFocusTimer() }
        }
        focusTimerView = tv
        val lp = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Settings.canDrawOverlays(context))
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            x = windowLayoutParams.x
            y = windowLayoutParams.y - 80
        }
        runCatching { windowManager.addView(focusTimerView, lp) }
    }

    private fun removeFocusTimer() {
        focusTimerJob?.cancel()
        focusTimerJob = null
        runCatching { focusTimerView?.let { windowManager.removeView(it) } }
        focusTimerView = null
    }

    private fun toggleFocusTimer() {
        if (isFocusTimerRunning) {
            focusTimerJob?.cancel()
            isFocusTimerRunning = false
        } else {
            isFocusTimerRunning = true
            focusTimeSeconds = 25 * 60
            focusTimerJob = scope.launch {
                while (focusTimeSeconds > 0 && isFocusTimerRunning) {
                    val min = focusTimeSeconds / 60
                    val sec = focusTimeSeconds % 60
                    withContext(Dispatchers.Main) {
                        focusTimerView?.text = String.format("%02d:%02d", min, sec)
                    }
                    kotlinx.coroutines.delay(1000)
                    focusTimeSeconds--
                }
                withContext(Dispatchers.Main) {
                    focusTimerView?.text = "Done! 🎉"
                    isFocusTimerRunning = false
                }
            }
        }
    }

    // ===== SNAP TO EDGE =====
    private fun snapToEdge() {
        if (!viewModel.getBooleanSp("snap_to_edge", true)) return
        val targetX = if (windowLayoutParams.x < 0) {
            (realScreenWidth / -2) + (windowLayoutParams.width / 2) - screenPaddingX
        } else {
            (realScreenWidth / 2) - (windowLayoutParams.width / 2) + screenPaddingX
        }
        ValueAnimator.ofInt(windowLayoutParams.x, targetX).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                runCatching {
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        x = it.animatedValue as Int
                    })
                }
            }
            start()
        }
    }

    // ===== PERFORMANCE OVERLAY =====
    private var perfOverlayView: android.widget.TextView? = null
    private var perfOverlayJob: kotlinx.coroutines.Job? = null

    private fun initPerfOverlay() {
        if (!viewModel.getBooleanSp("show_perf_overlay", false)) {
            removePerfOverlay()
            return
        }
        removePerfOverlay()
        val tv = android.widget.TextView(context).apply {
            setTextColor(android.graphics.Color.GREEN)
            textSize = 10f
            setBackgroundColor(0x88000000.toInt())
            setPadding(8, 4, 8, 4)
        }
        perfOverlayView = tv
        val lp = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Settings.canDrawOverlays(context))
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            x = windowLayoutParams.x + windowLayoutParams.width - 100
            y = windowLayoutParams.y
        }
        runCatching { windowManager.addView(perfOverlayView, lp) }
        perfOverlayJob = scope.launch {
            val runtime = Runtime.getRuntime()
            while (isActive) {
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val totalMem = runtime.totalMemory() / 1024 / 1024
                withContext(Dispatchers.Main) {
                    perfOverlayView?.text = "RAM: ${usedMem}/${totalMem}MB"
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private fun removePerfOverlay() {
        perfOverlayJob?.cancel()
        perfOverlayJob = null
        runCatching { perfOverlayView?.let { windowManager.removeView(it) } }
        perfOverlayView = null
    }

    // Swipe dari bawah → home
    private var swipeHomeStartX = 0f
    private var swipeHomeStartY = 0f
    private var isSwipeHomeTracking = false
    private val SWIPE_HOME_EDGE_HEIGHT = 60f
    private val SWIPE_HOME_MIN_DISTANCE = 100f

    private fun handleSwipeHomeGesture(event: MotionEvent): Boolean {
        if (!enableSwipeHome) return false
        val edgeHeight = SWIPE_HOME_EDGE_HEIGHT * context.resources.displayMetrics.density
        val minDistance = SWIPE_HOME_MIN_DISTANCE * context.resources.displayMetrics.density
        val viewHeight = binding.textureView.height.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y >= viewHeight - edgeHeight) {
                    swipeHomeStartX = event.x
                    swipeHomeStartY = event.y
                    isSwipeHomeTracking = true
                } else isSwipeHomeTracking = false
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeHomeTracking) {
                    val dy = swipeHomeStartY - event.y
                    val dx = kotlin.math.abs(event.x - swipeHomeStartX)
                    if (dy >= minDistance && dx <= 80f * context.resources.displayMetrics.density) {
                        isSwipeHomeTracking = false
                        performHomeKey()
                        return true
                    }
                }
                isSwipeHomeTracking = false
            }
            MotionEvent.ACTION_CANCEL -> isSwipeHomeTracking = false
        }
        return false
    }

    private fun performHomeKey() {
        runCatching {
            val downEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0)
            val upEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(downEvent, 0)
                setDisplayIdMethod?.invoke(upEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, 0)
            }
        }
    }

    // Swipe dari kanan → forward
    private var swipeForwardStartX = 0f
    private var swipeForwardStartY = 0f
    private var isSwipeForwardTracking = false
    private val SWIPE_FORWARD_EDGE_WIDTH = 60f
    private val SWIPE_FORWARD_MIN_DISTANCE = 100f

    private fun handleSwipeForwardGesture(event: MotionEvent): Boolean {
        if (!enableSwipeForward) return false
        val edgeWidth = SWIPE_FORWARD_EDGE_WIDTH * context.resources.displayMetrics.density
        val minDistance = SWIPE_FORWARD_MIN_DISTANCE * context.resources.displayMetrics.density
        val viewWidth = binding.textureView.width.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x >= viewWidth - edgeWidth) {
                    swipeForwardStartX = event.x
                    swipeForwardStartY = event.y
                    isSwipeForwardTracking = true
                } else isSwipeForwardTracking = false
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeForwardTracking) {
                    val dx = swipeForwardStartX - event.x
                    val dy = kotlin.math.abs(event.y - swipeForwardStartY)
                    if (dx >= minDistance && dy <= 80f * context.resources.displayMetrics.density) {
                        isSwipeForwardTracking = false
                        performForwardKey()
                        return true
                    }
                }
                isSwipeForwardTracking = false
            }
            MotionEvent.ACTION_CANCEL -> isSwipeForwardTracking = false
        }
        return false
    }

    private fun performForwardKey() {
        runCatching {
            val downEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD, 0)
            val upEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(downEvent, 0)
                setDisplayIdMethod?.invoke(upEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, 0)
            }
        }
    }

    // Pinch to resize
    private fun getPinchDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun handlePinchResize(event: MotionEvent): Boolean {
        if (!enablePinchResize || isFloating) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    pinchStartDistance = getPinchDistance(event)
                    pinchStartWidth = freeformWidth
                    pinchStartHeight = freeformHeight
                    isPinching = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && event.pointerCount == 2) {
                    val currentDistance = getPinchDistance(event)
                    val scale = currentDistance / pinchStartDistance
                    val newWidth = (pinchStartWidth * scale).roundToInt()
                    val newHeight = (pinchStartHeight * scale).roundToInt()
                    if (newWidth in minFreeformWidth..maxFreeformWidth &&
                        newHeight in minFreeformHeight..maxFreeformHeight) {
                        freeformWidth = newWidth
                        freeformHeight = newHeight
                        mScaleX = freeformWidth / rootWidth.toFloat()
                        mScaleY = freeformHeight / rootHeight.toFloat()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (isPinching) {
                    isPinching = false
                    // Apply resize ke VirtualDisplay
                    freeformScreenWidth = (freeformWidth - cardWidthMargin).roundToInt()
                    freeformScreenHeight = (freeformHeight - cardHeightMargin).roundToInt()
                    resizeVirtualDisplay()
                    
                    // Simpan ukuran
                    if (rememberFreeformSize) {
                        if (FreeformHelper.screenIsPortrait(screenRotation)) {
                            savedWidthPortrait = freeformWidth
                            savedHeightPortrait = freeformHeight
                        } else {
                            savedWidthLandscape = freeformWidth
                            savedHeightLandscape = freeformHeight
                        }
                    }
                }
            }
        }
        return false
    }

    private inner class TouchListener : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (handlePinchResize(event)) return true
            if (handleSwipeBackGesture(event)) return true
            if (handleSwipeHomeGesture(event)) return true
            if (handleSwipeForwardGesture(event)) return true
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> touchId = R.id.textureView
                MotionEvent.ACTION_UP -> touchId = -1
            }
            return true
        }

        private fun handleTouch(event: MotionEvent) {
            val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
            val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
            for (i in 0 until event.pointerCount) {
                val oldCoords = MotionEvent.PointerCoords()
                val pointerProperty = MotionEvent.PointerProperties()
                event.getPointerCoords(i, oldCoords)
                event.getPointerProperties(i, pointerProperty)
                pointerCoords[i] = oldCoords
                pointerCoords[i]!!.apply {
                    x = oldCoords.x / scaleX
                    y = oldCoords.y / scaleY
                }
                pointerProperties[i] = pointerProperty
            }
            val newEvent = MotionEvent.obtain(
                event.downTime, event.eventTime, event.action, event.pointerCount,
                pointerProperties, pointerCoords, event.metaState, event.buttonState,
                event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
                event.source, event.flags
            )
            setDisplayIdMethod?.invoke(newEvent, virtualDisplay.display.displayId)
            inputManager.injectInputEvent(newEvent, 0)
            newEvent.recycle()
        }
    }

    private inner class TouchListenerPreQ : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (handlePinchResize(event)) return true
            if (handleSwipeBackGesture(event)) return true
            if (handleSwipeHomeGesture(event)) return true
            if (handleSwipeForwardGesture(event)) return true
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> touchId = R.id.textureView
                MotionEvent.ACTION_UP -> touchId = -1
            }
            return true
        }

        private fun handleTouch(event: MotionEvent) {
            val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
            val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
            for (i in 0 until event.pointerCount) {
                val oldCoords = MotionEvent.PointerCoords()
                val pointerProperty = MotionEvent.PointerProperties()
                event.getPointerCoords(i, oldCoords)
                event.getPointerProperties(i, pointerProperty)
                pointerCoords[i] = oldCoords
                pointerCoords[i]!!.apply {
                    x = oldCoords.x / scaleX
                    y = oldCoords.y / scaleY
                }
                pointerProperties[i] = pointerProperty
            }
            val newEvent = MotionEvent.obtain(
                event.downTime, event.eventTime, event.action, event.pointerCount,
                pointerProperties, pointerCoords, event.metaState, event.buttonState,
                event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
                event.source, event.flags
            )
            inputManager.injectInputEvent(newEvent, virtualDisplay.display.displayId)
            newEvent.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class MTaskStackListener : TaskStackListener() {
        override fun onTaskCreated(tId: Int, componentName: ComponentName?) {}
        override fun onTaskRemoved(taskId: Int) {
            if (isDestroy) return
            taskList.remove(taskId)
            if (taskList.isEmpty()) {
                scope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(500)
                    if (!isDestroy && taskList.isEmpty()) destroy()
                }
            }
        }
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            if (isDestroy) return
            taskList.remove(taskInfo.taskId)
        }
        override fun onTaskDisplayChanged(tId: Int, newDisplayId: Int) {
            if (isDestroy) return
            if (newDisplayId == virtualDisplay.display.displayId) {
                if (!taskList.contains(tId)) taskList.add(tId)
                return
            }
            if (!taskList.contains(tId)) return
            if (newDisplayId == Display.DEFAULT_DISPLAY) {
                if (isFloating) {
                    context.startService(
                        Intent(context, FreeformService::class.java)
                            .setAction(FreeformService.ACTION_START_INTENT)
                            .putExtra(Intent.EXTRA_INTENT, config.intent)
                    )
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (config.useSuiRefuseToFullScreen) {
                        runCatching {
                            activityTaskManager.moveRootTaskToDisplay(tId, virtualDisplay.display.displayId)
                        }
                    } else {
                        context.startService(
                            Intent(context, FreeformService::class.java)
                                .setAction(FreeformService.ACTION_CALL_INTENT)
                                .putExtra(Intent.EXTRA_INTENT, config.intent)
                                .putExtra(FreeformService.EXTRA_DISPLAY_ID, virtualDisplay.display.displayId)
                        )
                    }
                }
            }
        }
        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskList.contains(tId) && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) { onFreeFormRotationChanged() }
            }
        }
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskList.contains(tId) && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) { onFreeFormRotationChanged() }
            }
        }
    }

    companion object {
        private const val TAG = "FreeformView"
        const val REMEMBER_X = "freeform_remember_x"
        const val REMEMBER_Y = "freeform_remember_y"
        const val REMEMBER_LAND_X = "freeform_remember_land_x"
        const val REMEMBER_LAND_Y = "freeform_remember_land_y"
        const val REMEMBER_HEIGHT = "freeform_remember_height"
        const val REMEMBER_LAND_HEIGHT = "freeform_remember_land_height"
        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0
    }
}onfig.intent)
                                .putExtra(FreeformService.EXTRA_DISPLAY_ID, virtualDisplay.display.displayId)
                        )
                    }
                }
            }
        }
        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskList.contains(tId) && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) { onFreeFormRotationChanged() }
            }
        }
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskList.contains(tId) && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) { onFreeFormRotationChanged() }
            }
        }
    }

    companion object {
        private const val TAG = "FreeformView"
        const val REMEMBER_X = "freeform_remember_x"
        const val REMEMBER_Y = "freeform_remember_y"
        const val REMEMBER_LAND_X = "freeform_remember_land_x"
        const val REMEMBER_LAND_Y = "freeform_remember_land_y"
        const val REMEMBER_HEIGHT = "freeform_remember_height"
        const val REMEMBER_LAND_HEIGHT = "freeform_remember_land_height"
        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0
    }
}