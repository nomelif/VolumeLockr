package com.klee.volumelockr

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.RangeSlider
import com.klee.volumelockr.databinding.VolumeCardBinding
import com.klee.volumelockr.service.VolumeService
import com.klee.volumelockr.ui.SettingsFragment
import kotlin.math.max;
import kotlin.math.min;

class VolumeAdapter(
    private var mVolumeList: List<Volume>,
    private var mService: VolumeService?,
    private var mContext: Context
) :
    RecyclerView.Adapter<VolumeAdapter.ViewHolder>() {

    private var mAudioManager: AudioManager =
        mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @MainThread
    fun update(volumes: List<Volume>) {
        mVolumeList = volumes
        update()
    }

    @MainThread
    fun update() {
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: VolumeCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = VolumeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val volume = mVolumeList[position]
        holder.binding.mediaTextView.text = volume.name
        val fractional_volume = volume.value.toFloat() / volume.max.toFloat()

        // Change bar state only if the whole thing is selected or the range is pinched to a point

        if((holder.binding.seekBar.values[0] == 0.0f && holder.binding.seekBar.values[1] == 1.0f) ||
           (holder.binding.seekBar.values[0] == holder.binding.seekBar.values[1])) {

            holder.binding.seekBar.setValues(fractional_volume, fractional_volume)

        }

        var volumeFrom = volume.copy()
        volumeFrom.value = (volume.max * holder.binding.seekBar.values[0]).toInt()


        var volumeTo = volume.copy()
        volumeTo.value = (volume.max * holder.binding.seekBar.values[1]).toInt()

        registerSeekBarCallback(holder, volume)
        registerSwitchButtonCallback(holder, volumeFrom, volumeTo)

        loadLockFromService(holder, volume)

        handleRingerMode(holder, volume)

        if (isPasswordProtected()) {
            holder.binding.seekBar.isEnabled = false
            holder.binding.switchButton.isEnabled = false
        }
    }

    private fun registerSeekBarCallback(holder: ViewHolder, volume: Volume) {
        val listener =
            RangeSlider.OnChangeListener { slider, value, fromUser ->

                // Compute minimum, maximum and update volume

                val volume_from = (slider.values[0] * volume.max).toInt()
                val volume_to = (slider.values[1] * volume.max).toInt()
                val updated_volume = min(volume_to, max(volume_from, volume.value))

                if (volume.stream != AudioManager.STREAM_NOTIFICATION || mService?.getMode() == 2) {
                    mAudioManager.setStreamVolume(volume.stream, updated_volume, 0)
                }

                volume.value = updated_volume
            }
        holder.binding.seekBar.addOnChangeListener(listener)
    }

    private fun registerSwitchButtonCallback(holder: ViewHolder, volumeFrom: Volume, volumeTo: Volume) {
        holder.binding.switchButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                onVolumeLocked(holder, volumeFrom, volumeTo)
            } else {
                onVolumeUnlocked(holder, volumeFrom)
            }
        }
    }

    private fun loadLockFromService(holder: ViewHolder, volume: Volume) {
        val locks = mService?.getLocks()?.keys
        locks?.let {
            for (key in it) {
                if (volume.stream == key) {
                    holder.binding.switchButton.isChecked = true
                    holder.binding.seekBar.isEnabled = false
                }
            }
        }
    }

    private fun adjustService() {
        mService?.getLocks()?.let {
            if (it.size > 0) {
                mService?.startLocking()
            } else {
                mService?.stopLocking()
            }
        }
    }

    private fun adjustNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mService?.getLocks()?.let {
                if (it.size > 0) {
                    mService?.tryShowNotification()
                } else {
                    mService?.tryHideNotification()
                }
            }
        }
    }

    private fun handleRingerMode(holder: ViewHolder, volume: Volume) {
        if (volume.stream == AudioManager.STREAM_NOTIFICATION) {
            holder.binding.seekBar.isEnabled =
                mService?.getMode() == 2 &&
                mService?.getLocks()?.containsKey(AudioManager.STREAM_NOTIFICATION) == false
        }
    }

    private fun onVolumeLocked(holder: ViewHolder, volumeFrom: Volume, volumeTo: Volume) {
        mService?.let {
            it.addLock(volumeFrom.stream, volumeFrom.value, volumeTo.value)
            adjustService()
            adjustNotification()
            holder.binding.seekBar.isEnabled = false
        }
    }

    private fun onVolumeUnlocked(holder: ViewHolder, volume: Volume) {
        mService?.let {
            it.removeLock(volume.stream)
            adjustService()
            adjustNotification()
            holder.binding.seekBar.isEnabled = true
        }
    }

    private fun isPasswordProtected(): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)
        return sharedPreferences.getBoolean(SettingsFragment.PASSWORD_PROTECTED_PREFERENCE, false)
    }

    override fun getItemCount(): Int {
        return mVolumeList.size
    }
}

data class Volume(
    val name: String,
    val stream: Int,
    var value: Int,
    val min: Int,
    val max: Int,
    var locked: Boolean
)
