package com.kayloh20.kyliemail.ui.compose

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kayloh20.kyliemail.KylieMailApp
import com.kayloh20.kyliemail.data.settings.ThemeMode
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme
import kotlin.math.roundToInt

/**
 * A compose window. On a Googlebook it opens as its own window beside the inbox; on a phone it
 * fills the screen. Each draft gets its own window.
 */
class ComposeActivity : ComponentActivity() {
    private lateinit var viewModel: ComposeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KylieMailApp).container
        val draftId = intent.getLongExtra(EXTRA_DRAFT_ID, -1)
        if (draftId < 0) {
            finish()
            return
        }
        container.notifier.clearSendFailure(draftId)
        viewModel = ViewModelProvider(
            this,
            viewModelFactory { initializer { ComposeViewModel(draftId, container.outbox, container.settings, container.database, container.credentials, container.attachments) } },
        )[ComposeViewModel::class.java]

        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // The window's title follows the subject, so several open drafts can be told apart.
            LaunchedEffect(viewModel.title) {
                @Suppress("DEPRECATION")
                setTaskDescription(ActivityManager.TaskDescription(viewModel.title))
                title = viewModel.title
            }
            KylieMailTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    ComposeScreen(viewModel, onFinished = ::finish, modifier = Modifier.safeDrawingPadding())
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) viewModel.saveNow()
    }

    override fun onDestroy() {
        // Closing the window any other way (its close button, for example) still saves the draft.
        if (::viewModel.isInitialized && isFinishing && !viewModel.handled) {
            val container = (application as KylieMailApp).container
            viewModel.closeFrom(container.appScope)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_DRAFT_ID = "draft_id"

        fun intent(context: Context, draftId: Long): Intent =
            Intent(context, ComposeActivity::class.java)
                .putExtra(EXTRA_DRAFT_ID, draftId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens a draft in a window about the size of a desktop mail composer. */
        fun open(context: Context, draftId: Long) {
            val metrics = context.resources.displayMetrics
            val width = (720 * metrics.density).roundToInt().coerceAtMost(metrics.widthPixels)
            val height = (680 * metrics.density).roundToInt().coerceAtMost(metrics.heightPixels)
            val left = (metrics.widthPixels - width) / 2
            val top = (metrics.heightPixels - height) / 2
            // Only used where windows are freely sized, like a Googlebook; ignored on phones.
            val options = ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, left + width, top + height))
            context.startActivity(intent(context, draftId), options.toBundle())
        }
    }
}
