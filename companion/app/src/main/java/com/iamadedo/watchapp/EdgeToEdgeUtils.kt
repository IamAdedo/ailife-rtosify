package com.iamadedo.watchapp

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.updateLayoutParams
import android.view.ViewGroup

object EdgeToEdgeUtils {
    /**
     * Enables edge-to-edge and automatically handles insets for common views.
     * By default, it applies top padding for the status bar and bottom padding for the navigation bar
     * to the root view or a specified main view.
     */
    fun applyEdgeToEdge(activity: ComponentActivity, rootView: View? = null) {
        activity.enableEdgeToEdge()
        
        val viewToApply = rootView ?: activity.window.decorView.findViewById(android.R.id.content)
        
        val initialPaddingLeft = viewToApply.paddingLeft
        val initialPaddingTop = viewToApply.paddingTop
        val initialPaddingRight = viewToApply.paddingRight
        val initialPaddingBottom = viewToApply.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(viewToApply) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = initialPaddingLeft + systemBars.left,
                top = initialPaddingTop + systemBars.top,
                right = initialPaddingRight + systemBars.right,
                bottom = initialPaddingBottom + systemBars.bottom
            )
            insets
        }
    }

    /**
     * Specialized version for activities with AppBarLayout/Toolbar.
     * Applies top padding to the AppBarLayout and bottom padding to the main content container.
     */
    fun applyEdgeToEdgeWithToolbar(
        activity: ComponentActivity,
        appBarLayout: View?,
        container: View?
    ) {
        activity.enableEdgeToEdge()

        appBarLayout?.let { abl ->
            ViewCompat.setOnApplyWindowInsetsListener(abl) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(top = systemBars.top)
                insets
            }
        }

        container?.let { c ->
            ViewCompat.setOnApplyWindowInsetsListener(c) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.updatePadding(bottom = systemBars.bottom)
                insets
            }
        }
    }

    fun Int.dpToPx(context: android.content.Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}
