package com.tufein.switchrecomphelper

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.tufein.switchrecomphelper.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private var logVisible = false

    private val pickKeys = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importKeys) }
    private val pickXci = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setXci) }
    private val pickOut = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.setOut(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null && !OnboardingActivity.seen(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        b.help.setOnClickListener { startActivity(Intent(this, OnboardingActivity::class.java)) }
        b.pickKeys.setOnClickListener { pickKeys.launch(arrayOf("*/*")) }
        b.pickXci.setOnClickListener { pickXci.launch(arrayOf("*/*")) }
        b.pickOut.setOnClickListener { pickOut.launch(null) }
        b.start.setOnClickListener { vm.start(b.cbExefs.isChecked, b.cbRomfs.isChecked) }
        b.cancel.setOnClickListener { vm.cancel() }
        b.toggleLog.setOnClickListener {
            logVisible = !logVisible
            applyLogVisibility()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect(::render) }
        }
    }

    private fun applyLogVisibility() {
        b.log.visibility = if (logVisible && b.log.text.isNotEmpty()) View.VISIBLE else View.GONE
        b.toggleLog.setText(if (logVisible) R.string.details_hide else R.string.details_show)
    }

    private fun render(s: UiState) {
        b.keysStatus.text = s.keysText ?: getString(R.string.keys_none)
        b.xciStatus.text = s.xciName ?: getString(R.string.none_selected)
        b.outStatus.text = s.outName ?: getString(R.string.none_selected)
        b.badge1.text = if (s.keysText != null) "✓" else "1"
        b.badge2.text = if (s.xciUri != null) "✓" else "2"
        b.badge3.text = if (s.outUri != null) "✓" else "3"

        b.progressCard.visibility = if (s.running) View.VISIBLE else View.GONE
        b.progress.progress = s.progress
        b.percent.text = getString(R.string.percent_fmt, s.progress / 10)
        b.speed.text = if (s.speedBytesPerSec > 0) getString(R.string.speed_fmt, Formatter.formatFileSize(this, s.speedBytesPerSec)) else ""
        b.eta.text = if (s.etaSeconds >= 0) getString(R.string.eta_fmt, formatDuration(s.etaSeconds)) else getString(R.string.eta_calculating)
        b.currentFile.text = s.currentFile

        b.message.visibility = if (s.message.isEmpty()) View.GONE else View.VISIBLE
        b.message.text = s.message
        b.message.setTextColor(
            if (s.isError) MaterialColors.getColor(b.message, androidx.appcompat.R.attr.colorError)
            else MaterialColors.getColor(b.message, com.google.android.material.R.attr.colorOnSurface),
        )

        b.log.text = s.log
        b.toggleLog.visibility = if (s.log.isEmpty()) View.GONE else View.VISIBLE
        applyLogVisibility()

        val idle = !s.running
        b.start.isEnabled = idle
        b.pickKeys.isEnabled = idle
        b.pickXci.isEnabled = idle
        b.pickOut.isEnabled = idle
        b.cbExefs.isEnabled = idle
        b.cbRomfs.isEnabled = idle
        if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> getString(R.string.dur_s, seconds)
        seconds < 3600 -> getString(R.string.dur_ms, seconds / 60, seconds % 60)
        else -> getString(R.string.dur_hm, seconds / 3600, (seconds % 3600) / 60)
    }
}
