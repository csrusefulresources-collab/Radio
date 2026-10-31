package com.radio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.radio.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), RadioController.Listener {

    private lateinit var b: ActivityMainBinding
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioController.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnPrev.setOnClickListener { RadioController.previous() }
        b.btnNext.setOnClickListener { RadioController.next() }
        b.btnPlay.setOnClickListener { RadioController.togglePlay() }
        b.btnBand.setOnClickListener { RadioController.nextBand() }
        b.btnScan.setOnClickListener { RadioController.toggleScan() }
        b.btnSpeaker.setOnClickListener { RadioController.toggleMute() }
        b.btnStations.setOnClickListener { showStations() }
        b.btnSleep.setOnClickListener { RadioController.cycleSleepTimer() }
        b.btnAdd.setOnClickListener { showAddDialog() }
        b.btnRemove.setOnClickListener { confirmRemove() }

        HardwareFm.launchIntent(packageManager)?.let { intent ->
            b.btnDeviceFm.visibility = View.VISIBLE
            b.btnDeviceFm.setOnClickListener { startActivity(intent) }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        RadioController.addListener(this)
        render()
    }

    override fun onStop() {
        RadioController.removeListener(this)
        super.onStop()
    }

    override fun onRadioStateChanged() { render() }

    private fun showStations() {
        val list = RadioController.stationsInBand()
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.status_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val unit = getString(RadioController.band.unitRes)
        val names = list.map { if (it.frequency.isBlank()) it.name else "${it.name} (${it.frequency} $unit)" }
        AlertDialog.Builder(this)
            .setTitle(R.string.station_list_title)
            .setItems(names.toTypedArray()) { _, which -> RadioController.select(which) }
            .show()
    }

    private fun showAddDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.hint_name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val freq = EditText(this).apply {
            hint = getString(R.string.hint_freq)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val url = EditText(this).apply {
            hint = getString(R.string.hint_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(name); box.addView(freq); box.addView(url)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_title, getString(RadioController.band.nameRes)))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val link = url.text.toString().trim()
                if (!link.startsWith("http://") && !link.startsWith("https://")) {
                    Toast.makeText(this, R.string.add_error_url, Toast.LENGTH_LONG).show()
                } else {
                    val title = name.text.toString().trim().ifEmpty { link }
                    RadioController.addStation(title, freq.text.toString().trim(), link)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove() {
        val st = RadioController.currentStation() ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_confirm, st.name))
            .setPositiveButton(android.R.string.ok) { _, _ -> RadioController.removeCurrentStation() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render() {
        val c = RadioController
        val bandName = getString(c.band.nameRes)
        val nextBandName = getString(c.nextBandOf().nameRes)
        val st = c.currentStation()
        val hasStations = c.stationsInBand().isNotEmpty()

        b.tvBand.text = bandName
        b.tvFrequency.text =
            if (st == null || st.frequency.isBlank()) "—"
            else "${st.frequency} ${getString(c.band.unitRes)}"
        b.tvStation.text = st?.name ?: getString(R.string.status_empty)
        b.tvStatus.text = getString(
            when {
                !hasStations -> R.string.status_empty
                c.hasError -> R.string.status_error
                c.isScanning -> R.string.status_scanning
                c.isActive && c.isBuffering -> R.string.status_loading
                c.isActive -> R.string.status_playing
                else -> R.string.status_stopped
            }
        )

        b.btnPlay.setText(if (c.isActive) R.string.btn_pause else R.string.btn_play)
        b.btnPlay.setIconResource(if (c.isActive) R.drawable.ic_pause else R.drawable.ic_play)

        b.btnBand.text = getString(R.string.btn_band_fmt, bandName)
        b.btnBand.contentDescription = getString(R.string.cd_band, bandName, nextBandName)

        b.btnScan.setText(if (c.isScanning) R.string.btn_scan_stop else R.string.btn_scan)

        b.btnSpeaker.setText(if (c.isMuted) R.string.btn_speaker_off else R.string.btn_speaker_on)
        b.btnSpeaker.setIconResource(if (c.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)

        b.btnSleep.text =
            if (c.sleepMinutes == 0) getString(R.string.btn_sleep_off)
            else getString(R.string.btn_sleep_fmt, c.sleepMinutes)

        b.btnRemove.visibility = if (st?.isUser == true) View.VISIBLE else View.GONE

        listOf(b.btnPrev, b.btnNext, b.btnPlay, b.btnScan, b.btnStations).forEach { it.isEnabled = hasStations }
    }
}
