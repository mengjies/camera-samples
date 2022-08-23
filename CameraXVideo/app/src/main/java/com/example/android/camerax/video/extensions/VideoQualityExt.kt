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
package com.example.android.camerax.video.extensions

import androidx.camera.core.AspectRatio
import androidx.camera.video.Quality

/**
 * 一个辅助函数，用于从 QualitySelector 枚举中检索纵横比。
 */
fun Quality.getAspectRatio(quality: Quality): Int {
    return when {
        arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
            .contains(quality)   -> AspectRatio.RATIO_16_9
        (quality ==  Quality.SD) -> AspectRatio.RATIO_4_3
        else -> throw UnsupportedOperationException()
    }
}

/**
 * 从质量枚举中检索纵横比字符串的辅助函数.
 */
fun Quality.getAspectRatioString(quality: Quality, portraitMode:Boolean) :String {
    val hdQualities = arrayOf(Quality.UHD, Quality.FHD, Quality.HD)
    val ratio =
        when {
            hdQualities.contains(quality) -> Pair(16, 9)
            quality == Quality.SD         -> Pair(4, 3)
            else -> throw UnsupportedOperationException()
        }

    return if (portraitMode) "V,${ratio.second}:${ratio.first}"
           else "H,${ratio.first}:${ratio.second}"
}

/**
 * 从给定的 Video.Quality 对象中获取名称（字符串）.
 */
fun Quality.getNameString() :String {
    return when (this) {
        Quality.UHD -> "QUALITY_UHD(2160p)"
        Quality.FHD -> "QUALITY_FHD(1080p)"
        Quality.HD -> "QUALITY_HD(720p)"
        Quality.SD -> "QUALITY_SD(480p)"
        else -> throw IllegalArgumentException("Quality $this is NOT supported")
    }
}

/**
 * 将 Video.Quality 名称（一个字符串）转换为其 Quality 对象.
 */
fun Quality.getQualityObject(name:String) : Quality {
    return when (name) {
        Quality.UHD.getNameString() -> Quality.UHD
        Quality.FHD.getNameString() -> Quality.FHD
        Quality.HD.getNameString()  -> Quality.HD
        Quality.SD.getNameString()  -> Quality.SD
        else -> throw IllegalArgumentException("Quality string $name is NOT supported")
    }
}
