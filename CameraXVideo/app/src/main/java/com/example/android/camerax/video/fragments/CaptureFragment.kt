/**
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * 简单的应用程序，用于演示使用 Recorder 捕获 CameraX 视频（到本地文件），具有以下简单控制：
 *   - 用户开始捕获
 *   - 此应用程序禁用所有 UI 选择。
 *   - 此应用程序启用捕获运行时 UI (pause/resume/stop).
 *   - 用户使用运行时 UI 控制录制，最终点击“停止”结束.
 *   - 此应用程序通过recording.stop()（或recording.close()）通知CameraX 录制停止.
 *   - CameraX 通过 Finalize 事件通知此应用程序确实停止了录制.
 *   - 此应用启动 VideoViewer fragment 以查看捕获的结果.
*/

package com.example.android.camerax.video.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import java.text.SimpleDateFormat
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.example.android.camerax.video.R
import com.example.android.camerax.video.databinding.FragmentCaptureBinding
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.whenCreated
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.android.camera.utils.GenericListAdapter
import com.example.android.camerax.video.extensions.getAspectRatio
import com.example.android.camerax.video.extensions.getAspectRatioString
import com.example.android.camerax.video.extensions.getNameString
import kotlinx.coroutines.*
import java.util.*

class CaptureFragment : Fragment() {

    // UI with ViewBinding
    private var _captureViewBinding: FragmentCaptureBinding? = null
    private val captureViewBinding get() = _captureViewBinding!!
    private val captureLiveStatus = MutableLiveData<String>()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private val cameraCapabilities = mutableListOf<CameraCapability>()

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordingState:VideoRecordEvent

    // 相机 UI 状态和输入
    enum class UiState {
        IDLE,       // 不录制，所有 UI 控件都处于活动状态。
        RECORDING,  // 相机正在录制，仅显示暂停恢复和停止按钮。
        FINALIZED,  // 录制刚刚完成，禁用所有 RECORDING UI 控件.
        RECOVERY    // 供将来使用.
    }
    private var cameraIndex = 0
    private var qualityIndex = DEFAULT_QUALITY_IDX
    private var audioEnabled = false

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var enumerationDeferred:Deferred<Unit>? = null

    // cameraX 捕获主功能
    /**
     *   在此示例中始终绑定预览 + 视频捕获用例组合（VideoCapture 可以单独工作）。该函数应始终在主线程上执行。
     */
    private suspend fun bindCaptureUsecase() {
        val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        val cameraSelector = getCameraSelector(cameraIndex)

        // 创建用户所需的 QualitySelector（视频分辨率）：我们知道这是支持的，将创建一个有效的 qualitySelector。
        val quality = cameraCapabilities[cameraIndex].qualities[qualityIndex]
        val qualitySelector = QualitySelector.from(quality)

        captureViewBinding.previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            val orientation = this@CaptureFragment.resources.configuration.orientation
            dimensionRatio = quality.getAspectRatioString(quality,
                (orientation == Configuration.ORIENTATION_PORTRAIT))
        }

        //preview
        val preview = Preview.Builder()
            .setTargetAspectRatio(quality.getAspectRatio(quality))
            .build().apply {
                setSurfaceProvider(captureViewBinding.previewView.surfaceProvider)
            }

        // 构建一个记录器，它可以：
        //   - 将视频音频录制到 MediaStore（仅在此处显示）、文件、ParcelFileDescriptor
        //   - 用于创建录音（录音执行录音）
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                videoCapture,
                preview
            )
        } catch (exc: Exception) {
            // 我们在主线程上，让我们重置 UI 上的控件.
            Log.e(TAG, "Use case binding failed", exc)
            resetUIandState("bindToLifecycle failed: $exc")
        }
        enableUI(true)
    }

    /**
     * 开始录制视频
     *   - 配置录制器以捕获到 MediaStoreOutput
     *   - 注册 RecordEvent 监听器
     *   - 应用来自用户的音频请求
     *   - 开始录制！
     * 使用此功能后，用户可以 start/pause/resume/stop 录制，应用程序会监听 VideoRecordEvent 以了解当前的录制状态。
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // 为我们的记录器创建 MediaStoreOutputOptions：生成我们的记录！
        val name = "CameraX-recording-" +
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireActivity().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // 配置 Recorder 并开始录制到 mediaStoreOutput。
        currentRecording = videoCapture.output
               .prepareRecording(requireActivity(), mediaStoreOutput)
               .apply { if (audioEnabled) withAudioEnabled() }
               .start(mainThreadExecutor, captureListener)

        Log.i(TAG, "Recording started")
    }

    /**
     * 捕获事件监听器.
     */
    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // 缓存录制状态
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
             // 显示捕获的视频
            lifecycleScope.launch {
                navController.navigate(
                    CaptureFragmentDirections.actionCaptureToVideoViewer(
                        event.outputResults.outputUri
                    )
                )
            }
        }
    }

    /**
     * 检索询问的相机类型（镜头朝向类型）。在此示例中，只有 2 种类型：
     *   idx 为偶数：CameraSelector.LENS_FACING_BACK
     *      奇数：CameraSelector.LENS_FACING_FRONT
     */
    private fun getCameraSelector(idx: Int) : CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i(TAG, "错误：此设备没有任何摄像头，正在退出")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].camSelector)
    }

    data class CameraCapability(val camSelector: CameraSelector, val qualities:List<Quality>)
    /**
     * 查询并缓存本平台的摄像头能力，只运行一次.
     */
    init {
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (camSelector in arrayOf(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        // 只需获取 camera.cameraInfo 即可查询功能
                        // 我们在这里不绑定任何东西.
                        if (provider.hasCamera(camSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                            QualitySelector
                                .getSupportedQualities(camera.cameraInfo)
                                .filter { quality ->
                                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                        .contains(quality)
                                }.also {
                                    cameraCapabilities.add(CameraCapability(camSelector, it))
                                }
                        }
                    } catch (exc: java.lang.Exception) {
                        Log.e(TAG, "Camera Face $camSelector is not supported")
                    }
                }
            }
        }
    }

    /**
     * 一次初始化 CameraFragment（作为 fragment 布局创建过程的一部分）.
     * 该函数执行以下操作：
     *   - 初始化但禁用除质量选择之外的所有 UI 控件.
     *   - 设置质量选择 recycler view.
     *   - 将用例绑定到生命周期相机，启用 UI 控件.
     */
    private fun initCameraFragment() {
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            initializeQualitySectionsUI()

            bindCaptureUsecase()
        }
    }

    /**
     * 初始化用户界面。在此功能中配置预览和捕获操作。
     * 请注意，预览和捕获都由 UI 或 CameraX 回调初始化（除了第一次在 onCreateView() 中进入此 fragment 时）
     */
    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        captureViewBinding.cameraButton.apply {
            setOnClickListener {
                cameraIndex = (cameraIndex + 1) % cameraCapabilities.size
                // 相机设备更改立即生效：
                //   - 重置质量选择 - 重新启动预览
                qualityIndex = DEFAULT_QUALITY_IDX
                initializeQualitySectionsUI()
                enableUI(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCaptureUsecase()
                }
            }
            isEnabled = false
        }

        // audioEnabled 默认为禁用.
        captureViewBinding.audioSelection.isChecked = audioEnabled
        captureViewBinding.audioSelection.setOnClickListener {
            audioEnabled = captureViewBinding.audioSelection.isChecked
        }

        // 对用户触摸捕获按钮做出反应
        captureViewBinding.captureButton.apply {
            setOnClickListener {
                if (!this@CaptureFragment::recordingState.isInitialized ||
                    recordingState is VideoRecordEvent.Finalize)
                {
                    enableUI(false)  // 我们的 eventListener 将打开 Recording UI.
                    startRecording()
                } else {
                    when (recordingState) {
                        is VideoRecordEvent.Start -> {
                            currentRecording?.pause()
                            captureViewBinding.stopButton.visibility = View.VISIBLE
                        }
                        is VideoRecordEvent.Pause -> currentRecording?.resume()
                        is VideoRecordEvent.Resume -> currentRecording?.pause()
                        else -> throw IllegalStateException("recordingState in unknown state")
                    }
                }
            }
            isEnabled = false
        }

        captureViewBinding.stopButton.apply {
            setOnClickListener {
                // 停止：在我们查看 fragment 之前点击后隐藏它
                captureViewBinding.stopButton.visibility = View.INVISIBLE
                if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
                    return@setOnClickListener
                }

                val recording = currentRecording
                if (recording != null) {
                    recording.stop()
                    currentRecording = null
                }
                captureViewBinding.captureButton.setImageResource(R.drawable.ic_start)
            }
            // 确保停止按钮被初始化禁用和不可见
            visibility = View.INVISIBLE
            isEnabled = false
        }

        captureLiveStatus.observe(viewLifecycleOwner) {
            captureViewBinding.captureStatus.apply {
                post { text = it }
            }
        }
        captureLiveStatus.value = getString(R.string.Idle)
    }

    /**
     * 根据 CameraX VideoRecordEvent 类型更新UI：
     *   - 用户开始捕获.
     *   - 此应用禁用所有 UI 选择.
     *   - 此应用程序启用捕获运行时 UI (pause/resume/stop).
     *   - 用户使用运行时 UI 控制录制，最终点击“停止”结束.
     *   - 此应用程序通过recording.stop()（或recording.close()）通知CameraX 录制停止.
     *   - CameraX 通过 Finalize 事件通知此应用程序确实停止了录制.
     *   - 此应用启动 VideoViewer fragment 以查看捕获的结果.
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getNameString()
                    else event.getNameString()
        when (event) {
                is VideoRecordEvent.Status -> {
                    // 占位符：我们在这个 when() 块之后用新的状态更新 UI，
                    // 这里不需要做任何事情。
                }
                is VideoRecordEvent.Start -> {
                    showUI(UiState.RECORDING, event.getNameString())
                }
                is VideoRecordEvent.Finalize-> {
                    showUI(UiState.FINALIZED, event.getNameString())
                }
                is VideoRecordEvent.Pause -> {
                    captureViewBinding.captureButton.setImageResource(R.drawable.ic_resume)
                }
                is VideoRecordEvent.Resume -> {
                    captureViewBinding.captureButton.setImageResource(R.drawable.ic_pause)
                }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        var text = "${state}: recorded ${size}KB, in ${time}second"
        if(event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        captureLiveStatus.value = text
        Log.i(TAG, "recording event: $text")
    }

    /**
     * 启用禁用 UI：
     *    用户可以在录制不在会话中时选择捕获参数，一旦开始录制，需要禁用可用的 UI 以避免冲突。
     */
    private fun enableUI(enable: Boolean) {
        arrayOf(captureViewBinding.cameraButton,
                captureViewBinding.captureButton,
                captureViewBinding.stopButton,
                captureViewBinding.audioSelection,
                captureViewBinding.qualitySelection).forEach {
                    it.isEnabled = enable
        }
        // 如果没有设备可以切换，请禁用相机按钮
        if (cameraCapabilities.size <= 1) {
            captureViewBinding.cameraButton.isEnabled = false
        }
        // 如果没有要切换的分辨率，则禁用分辨率列表
        if (cameraCapabilities[cameraIndex].qualities.size <= 1) {
            captureViewBinding.qualitySelection.apply { isEnabled = false }
        }
    }

    /**
     * 初始化用于录制的 UI：
     *   - 记录：隐藏音频，质量选择，更改摄像头UI；启用停止按钮
     *   - 否则：显示除停止按钮外
     */
    private fun showUI(state: UiState, status:String = "idle") {
        captureViewBinding.let {
            when(state) {
                UiState.IDLE -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE

                    it.cameraButton.visibility= View.VISIBLE
                    it.audioSelection.visibility = View.VISIBLE
                    it.qualitySelection.visibility=View.VISIBLE
                }
                UiState.RECORDING -> {
                    it.cameraButton.visibility = View.INVISIBLE
                    it.audioSelection.visibility = View.INVISIBLE
                    it.qualitySelection.visibility = View.INVISIBLE

                    it.captureButton.setImageResource(R.drawable.ic_pause)
                    it.captureButton.isEnabled = true
                    it.stopButton.visibility = View.VISIBLE
                    it.stopButton.isEnabled = true
                }
                UiState.FINALIZED -> {
                    it.captureButton.setImageResource(R.drawable.ic_start)
                    it.stopButton.visibility = View.INVISIBLE
                }
                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    Log.e(TAG, errorMsg)
                    return
                }
            }
            it.captureStatus.text = status
        }
    }

    /**
     * 重置 UI（重新启动）：
     *    如果绑定失败，让我们再给它一个更改以重试。在未来的情况下，我们可能会失败并且用户会收到有关状态的通知
     */
    private fun resetUIandState(reason: String) {
        enableUI(true)
        showUI(UiState.IDLE, reason)

        cameraIndex = 0
        qualityIndex = DEFAULT_QUALITY_IDX
        audioEnabled = false
        captureViewBinding.audioSelection.isChecked = audioEnabled
        initializeQualitySectionsUI()
    }

    /**
     *  initializeQualitySectionsUI():
     *    填充 RecyclerView 以显示相机功能：
     *    - 一个正面
     *    - 一个背面
     *    用户选择保存到 qualityIndex，将在 bindCaptureUsecase() 中使用。
     */
    private fun initializeQualitySectionsUI() {
        val selectorStrings = cameraCapabilities[cameraIndex].qualities.map {
            it.getNameString()
        }
        // 创建适配器到质量选择 RecyclerView
        captureViewBinding.qualitySelection.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = GenericListAdapter(
                selectorStrings,
                itemLayoutId = R.layout.video_quality_item
            ) { holderView, qcString, position ->

                holderView.apply {
                    findViewById<TextView>(R.id.qualityTextView)?.text = qcString
                    // 选择默认质量选择器
                    isSelected = (position == qualityIndex)
                }

                holderView.setOnClickListener { view ->
                    if (qualityIndex == position) return@setOnClickListener

                    captureViewBinding.qualitySelection.let {
                        // 取消选择 UI 上的先前选择.
                        it.findViewHolderForAdapterPosition(qualityIndex)
                            ?.itemView
                            ?.isSelected = false
                    }
                    // 打开 UI 上的新选择.
                    view.isSelected = true
                    qualityIndex = position

                    // 重新绑定用例以将新的 QualitySelection 付诸实施.
                    enableUI(false)
                    viewLifecycleOwner.lifecycleScope.launch {
                        bindCaptureUsecase()
                    }
                }
            }
            isEnabled = false
        }
    }

    // System function implementations
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _captureViewBinding = FragmentCaptureBinding.inflate(inflater, container, false)
        return captureViewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }
    override fun onDestroyView() {
        _captureViewBinding = null
        super.onDestroyView()
    }

    companion object {
        // 如果没有来自 UI 的输入，则默认质量选择
        const val DEFAULT_QUALITY_IDX = 0
        val TAG:String = CaptureFragment::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}