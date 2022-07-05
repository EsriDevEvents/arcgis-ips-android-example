package com.esri.ucexample.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.esri.arcgisruntime.ArcGISRuntimeException
import com.esri.arcgisruntime.location.IndoorsLocationDataSource
import com.esri.arcgisruntime.location.LocationDataSource
import com.esri.arcgisruntime.location.WarningChangedEvent
import com.esri.arcgisruntime.location.WarningChangedListener
import com.esri.ucexample.R
import com.esri.ucexample.databinding.MainFragmentBinding

class MainFragment : Fragment(), LocationDataSource.LocationChangedListener,
    LocationDataSource.StatusChangedListener,
    WarningChangedListener, LocationDataSource.ErrorChangedListener {

    companion object {
        fun newInstance() = MainFragment()
    }

    private lateinit var viewModel: MainViewModel
    private lateinit var binding: MainFragmentBinding
    private var mStopping = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.none { value -> !value }) {
                viewModel.connectToPortal()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MainFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // Observe MuatbleLiveData
        viewModel.showMap.observe(requireActivity()) { map -> binding.mapView.map = map }
        viewModel.startIndoorsLocationDataSource.observe(requireActivity()) { model ->
            startLocationDataSource(model)
        }
        viewModel.showError.observe(requireActivity()) { res -> showAlert(res) }
        viewModel.showProgressBar.observe(requireActivity()) { showProgressBar(it) }

        val startStopButton = binding.startStopButton
        startStopButton.setOnClickListener {
            // Start or stop ILDS
            if (startStopButton.text == resources.getString(R.string.startILDSButton)) {
                viewModel.startIndoorsLocationDataSource(binding.mapView.map)
                setStartStopButtonVisibility(false)
            } else {
                mStopping = true
                stopLocationDisplay()
            }
        }

        if (savedInstanceState == null) {
            // Request permissions upon start-up
            if (requireContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                requireContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            ) {
                val requestPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,  /* !Needed since Android 12! */
                        Manifest.permission.BLUETOOTH_SCAN         /* !Needed since Android 12! */
                    )
                } else {
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION   /* !Needed since Android 12! */
                    )
                }

                requestPermissionLauncher.launch(requestPermissions)
            } else {
                showProgressBar(true)
                viewModel.connectToPortal()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.pause()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.resume()
    }

    // Start ILDS and wire it to the locationDisplay
    private fun startLocationDataSource(model: IndoorsLocationDataSourceModel) {
        val locationDisplay = binding.mapView.locationDisplay
        val indoorsLocationDataSource = IndoorsLocationDataSource(
            requireContext(),
            model.positioningTable,
            model.pathwaysTable,
            model.positioningId
        )

        locationDisplay.locationDataSource = indoorsLocationDataSource
        // locationDisplay.autoPanMode = LocationDisplay.AutoPanMode.COMPASS_NAVIGATION
        // These listeners will receive location, heading and status updates from the location data source.
        indoorsLocationDataSource.addStatusChangedListener(this)
        indoorsLocationDataSource.addLocationChangedListener(this)
        indoorsLocationDataSource.addErrorChangedListener(this)
        indoorsLocationDataSource.addWarningChangedListener(this)
        // Asynchronously start of the location display, which will in-turn start IndoorsLocationDataSource to start receiving IPS updates.
        indoorsLocationDataSource.startAsync()
    }

    // Stop ILDS: clean-up listener
    private fun stopLocationDisplay() {
        binding.locationDataView.isVisible = false
        viewModel.reset()

        val indoorsLocationDataSource =
            binding.mapView.locationDisplay.locationDataSource as IndoorsLocationDataSource
        indoorsLocationDataSource.stopAsync()
        indoorsLocationDataSource.removeLocationChangedListener(this)
        indoorsLocationDataSource.removeErrorChangedListener(this)
        indoorsLocationDataSource.removeWarningChangedListener(this)
    }

    // ILDS listener implementations
    override fun locationChanged(locationChangedEvent: LocationDataSource.LocationChangedEvent?) {
        locationChangedEvent?.let { updateUI(it.location) }
    }

    override fun statusChanged(statusChangedEvent: LocationDataSource.StatusChangedEvent) {
        when (statusChangedEvent.status) {
            LocationDataSource.Status.STARTING -> showProgressBar(true)
            LocationDataSource.Status.STARTED -> {
                binding.startStopButton.setText(R.string.stopILDSButton)
                showProgressBar(false)
                setStartStopButtonVisibility(true)
            }
            LocationDataSource.Status.FAILED_TO_START -> {
                showProgressBar(false)
                (statusChangedEvent.source.error as? ArcGISRuntimeException)?.let { exception ->
                    Log.d(
                        "MainFragment",
                        "ILDS failed to start. (${exception.cause?.localizedMessage}, ${exception.additionalMessage})"
                    )
                }
                // Use errorChanged listener to display error messages to the user
                setStartStopButtonVisibility(true)
            }
            LocationDataSource.Status.STOPPED -> {
                val locationDisplay = binding.mapView.locationDisplay
                locationDisplay.locationDataSource.removeStatusChangedListener(this)
                binding.startStopButton.setText(R.string.startILDSButton)
                if (mStopping) {
                    mStopping = false
                } else {
                    stopLocationDisplay()
                    (statusChangedEvent.source.error as? ArcGISRuntimeException)?.let { exception ->
                        Log.d(
                            "MainFragment",
                            "ILDS failed to start. (${exception.localizedMessage}, ${exception.additionalMessage})"
                        )
                    }
                    showAlert(R.string.error_ilds_stopped)
                }
            }
            else -> {}
        }
    }

    override fun warningChanged(event: WarningChangedEvent?) {
        val warningMessage =
            "${event?.warning?.cause?.localizedMessage ?: event?.warning?.localizedMessage} - ${event?.warning?.additionalMessage}"
        Log.d("MainFragment", warningMessage)
        Toast.makeText(requireContext(), warningMessage, Toast.LENGTH_SHORT).show()
    }

    override fun errorChanged(event: LocationDataSource.ErrorChangedEvent?) {
        val error = event?.error as? ArcGISRuntimeException
        error?.let {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle("Error")
            builder.setMessage("${error.localizedMessage} - ${error.additionalMessage}")
            builder.setPositiveButton(android.R.string.ok, null)
            builder.show()
        }
    }

    // Update UI with updated location
    private fun updateUI(location: LocationDataSource.Location) {
        val floor =
            location.additionalSourceProperties[LocationDataSource.Location.KEY_FLOOR] as? Int
        val positionSource =
            location.additionalSourceProperties[LocationDataSource.Location.KEY_POSITION_SOURCE] as? String
        val transmitterCount =
            location.additionalSourceProperties[LocationDataSource.Location.KEY_TRANSMITTER_COUNT] as? Long
        val networkCount =
            location.additionalSourceProperties[LocationDataSource.Location.KEY_SATELLITE_COUNT] as? Int

        binding.locationDataView.isVisible = true
        binding.floor.text = getString(R.string.debug_floor, floor)
        binding.positionSource.text = getString(R.string.debug_position_source, positionSource)
        binding.horizontalAccuracy.text =
            getString(R.string.debug_horizontal_accuracy, location.horizontalAccuracy)
        binding.senderCount.text =
            if (positionSource == LocationDataSource.Location.POSITION_SOURCE_GNSS) {
                getString(R.string.debug_network_count, networkCount)
            } else {
                getString(R.string.debug_transmitter_count, transmitterCount)
            }

        floor?.let {
            viewModel.updateFloor(binding.mapView.map, it)
        }
    }

    private fun showAlert(@StringRes textResource: Int) {
        showProgressBar(false)

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.alert_title)
        builder.setMessage(textResource)

        builder.setPositiveButton(android.R.string.ok, null)
        builder.show()
    }

    private fun setStartStopButtonVisibility(show: Boolean) {
        binding.startStopButton.isVisible = show
    }

    private fun showProgressBar(isVisible: Boolean) {
        binding.progressBarHolder.isVisible = isVisible
    }
}