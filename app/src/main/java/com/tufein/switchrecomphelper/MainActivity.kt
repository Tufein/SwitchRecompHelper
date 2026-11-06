package com.tufein.switchrecomphelper

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tufein.switchrecomphelper.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

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
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pickKeys.setOnClickListener { pickKeys.launch(arrayOf("*/*")) }
        b.pickXci.setOnClickListener { pickXci.launch(arrayOf("*/*")) }
        b.pickOut.setOnClickListener { pickOut.launch(null) }
        b.start.setOnClickListener { vm.start(b.cbExefs.isChecked, b.cbRomfs.isChecked) }
        b.cancel.setOnClickListener { vm.cancel() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { s ->
                    b.keysStatus.text = s.keysText ?: getString(R.string.keys_none)
                    b.xciStatus.text = s.xciName ?: getString(R.string.none_selected)
                    b.outStatus.text = s.outName ?: getString(R.string.none_selected)
                    b.progress.progress = s.progress
                    b.message.text = s.message
                    b.log.text = s.log
                    val idle = !s.running
                    b.start.isEnabled = idle
                    b.cancel.isEnabled = s.running
                    b.pickKeys.isEnabled = idle
                    b.pickXci.isEnabled = idle
                    b.pickOut.isEnabled = idle
                    if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }
}
