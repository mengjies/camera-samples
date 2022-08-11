/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.example.cameraxbasic.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.io.File
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.android.example.cameraxbasic.BuildConfig
import com.android.example.cameraxbasic.utils.padWithDisplayCutout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.android.example.cameraxbasic.utils.showImmersive
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.databinding.FragmentGalleryBinding
import java.util.Locale

val EXTENSION_WHITELIST = arrayOf("JPG")

/** Fragment used to present the user with a gallery of photos taken */
class GalleryFragment internal constructor() : Fragment() {

    /** Android ViewBinding */
    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null

    private val fragmentGalleryBinding get() = _fragmentGalleryBinding!!

    /** AndroidX navigation arguments */
    private val args: GalleryFragmentArgs by navArgs()

    private lateinit var mediaList: MutableList<File>

    /** Adapter class used to present a fragment containing one photo or video as a page */
    inner class MediaPagerAdapter(fm: FragmentManager) : FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getCount(): Int = mediaList.size
        override fun getItem(position: Int): Fragment = PhotoFragment.create(mediaList[position])
        override fun getItemPosition(obj: Any): Int = POSITION_NONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 将此标记为保留fragment，因此生命周期不会在配置更改时重新启动
        retainInstance = true

        // 从导航参数中获取媒体的根目录
        val rootDirectory = File(args.rootDirectory)

        // 遍历根目录中的所有文件
        // 我们颠倒列表的顺序，首先呈现最后一张照片
        mediaList = rootDirectory.listFiles { file ->
            EXTENSION_WHITELIST.contains(file.extension.toUpperCase(Locale.ROOT))
        }?.sortedDescending()?.toMutableList() ?: mutableListOf()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)
        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 检查媒体文件列表
        if (mediaList.isEmpty()) {
            fragmentGalleryBinding.deleteButton.isEnabled = false
            fragmentGalleryBinding.shareButton.isEnabled = false
        }

        // 填充 ViewPager 并实现两个媒体项的缓存
        fragmentGalleryBinding.photoViewPager.apply {
            offscreenPageLimit = 2
            adapter = MediaPagerAdapter(childFragmentManager)
        }

        // 确保切口“安全区域”避开屏幕缺口（如果有）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 使用扩展方法使用显示切口的边界填充包含 UI 的“内部”视图
            fragmentGalleryBinding.cutoutSafeArea.padWithDisplayCutout()
        }

        // 后退
        fragmentGalleryBinding.backButton.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }

        // 分享
        fragmentGalleryBinding.shareButton.setOnClickListener {

            mediaList.getOrNull(fragmentGalleryBinding.photoViewPager.currentItem)?.let { mediaFile ->

                // 分享 intent
                val intent = Intent().apply {
                    // 从文件扩展名推断媒体类型
                    val mediaType = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(mediaFile.extension)
                    // 从我们的 FileProvider 实现中获取 URI
                    val uri = FileProvider.getUriForFile(
                            view.context, BuildConfig.APPLICATION_ID + ".provider", mediaFile)
                    // 设置适当的额外意图、类型、操作和标志
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = mediaType
                    action = Intent.ACTION_SEND
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                // 启动意图，让用户选择要与之共享的应用程序
                startActivity(Intent.createChooser(intent, getString(R.string.share_hint)))
            }
        }

        // 删除
        fragmentGalleryBinding.deleteButton.setOnClickListener {

            mediaList.getOrNull(fragmentGalleryBinding.photoViewPager.currentItem)?.let { mediaFile ->

                AlertDialog.Builder(view.context, android.R.style.Theme_Material_Dialog)
                        .setTitle(getString(R.string.delete_title))
                        .setMessage(getString(R.string.delete_dialog))
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(android.R.string.yes) { _, _ ->

                            // 删除当前照片
                            mediaFile.delete()

                            // 发送相关广播通知其他应用删除
                            MediaScannerConnection.scanFile(
                                    view.context, arrayOf(mediaFile.absolutePath), null, null)

                            // 通知 view pager
                            mediaList.removeAt(fragmentGalleryBinding.photoViewPager.currentItem)
                            fragmentGalleryBinding.photoViewPager.adapter?.notifyDataSetChanged()

                            // 如果所有照片都已删除，请返回相机
                            if (mediaList.isEmpty()) {
                                Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
                            }

                        }

                        .setNegativeButton(android.R.string.no, null)
                        .create().showImmersive()
            }
        }
    }

    override fun onDestroyView() {
        _fragmentGalleryBinding = null
        super.onDestroyView()
    }
}
