package com.iamadedo.phoneapp.watchface

data class WatchFaceFileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0
)
