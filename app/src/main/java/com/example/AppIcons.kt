package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {
    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f)
                horizontalLineTo(18f)
                verticalLineTo(18f)
                horizontalLineTo(6f)
                close()
            }
        }.build()
    }

    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
                moveTo(17.3f, 11f)
                curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
                curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
                horizontalLineTo(5f)
                curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
                verticalLineTo(21f)
                horizontalLineTo(13f)
                verticalLineTo(17.72f)
                curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
                horizontalLineTo(17.3f)
                close()
            }
        }.build()
    }

    val FastForward: ImageVector by lazy {
        ImageVector.Builder(
            name = "FastForward",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(4f, 18f)
                lineTo(12.5f, 12f)
                lineTo(4f, 6f)
                verticalLineTo(18f)
                close()
                moveTo(13f, 6f)
                verticalLineTo(18f)
                lineTo(21.5f, 12f)
                lineTo(13f, 6f)
                close()
            }
        }.build()
    }

    val FastRewind: ImageVector by lazy {
        ImageVector.Builder(
            name = "FastRewind",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(11f, 18f)
                verticalLineTo(6f)
                lineTo(2.5f, 12f)
                lineTo(11f, 18f)
                close()
                moveTo(11.5f, 12f)
                lineTo(20f, 18f)
                verticalLineTo(6f)
                lineTo(11.5f, 12f)
                close()
            }
        }.build()
    }
}
