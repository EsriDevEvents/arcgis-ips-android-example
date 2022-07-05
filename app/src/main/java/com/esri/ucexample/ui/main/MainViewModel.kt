package com.esri.ucexample.ui.main

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esri.arcgisruntime.data.*
import com.esri.arcgisruntime.layers.FeatureLayer
import com.esri.arcgisruntime.loadable.LoadStatus
import com.esri.arcgisruntime.mapping.ArcGISMap
import com.esri.arcgisruntime.portal.Portal
import com.esri.arcgisruntime.portal.PortalItem
import com.esri.arcgisruntime.security.UserCredential
import com.esri.ucexample.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MainViewModel : ViewModel() {
    // Hard-wire portal credential and map to use
    private val portalURL = "https://viennardc.maps.arcgis.com"
    private val userName = "SurveyAppUser1"
    private val userPwd = "pwd.SurveyAppUser1"
    private val mapID = "598c664f9f594f45a4939988d03ff932"  // BLE test map

    // IPS_Positioning table name
    private val ipsPositioningTableName = "IPS_Positioning"

    // Setup MutableLiveData variables for UI updates
    val showMap = MutableLiveData<ArcGISMap>()
    val startIndoorsLocationDataSource = MutableLiveData<IndoorsLocationDataSourceModel>()
    val showError = MutableLiveData<Int>()
    val showProgressBar = MutableLiveData<Boolean>()

    // Hold currentFloor to minimize UI updates
    private var currentFloor: Int? = null

    // 1: Connect to portal
    fun connectToPortal() {
        viewModelScope.launch {
            val portal = Portal(portalURL, true)
            loadPortal(portal)
                .onSuccess {
                    val arcGisMap = ArcGISMap(PortalItem(portal, mapID))
                    if (loadMap(arcGisMap) == LoadStatus.LOADED) {
                        // Update UI: load map in MapView
                        showMap.postValue(arcGisMap)
                        // Load all necessary tables and start ILDS
                        loadMapDataAndStartIndoorsLocationDataSource(arcGisMap)
                    } else {
                        Log.d("MainViewModel", "Map load failed.")
                        showError.postValue(R.string.error_map_loading)
                    }
                }
                .onFailure {
                    Log.d("MainViewModel", "Map load failed. (${it.cause?.localizedMessage})")
                    showError.postValue(R.string.error_map_loading)
                }
        }
    }

    private suspend fun loadPortal(portal: Portal): ResultOf<Unit, Exception> =
        withContext(Dispatchers.Default) {
            try {
                portal.credential = UserCredential(userName, userPwd)
                portal.load()

                ResultOf.Success(Unit)
            } catch (error: Exception) {
                ResultOf.Failure(error)
            }
        }

    // 2: load specified map
    private suspend fun loadMap(agsMap: ArcGISMap): LoadStatus =
        withContext(Dispatchers.Default) {
            try {
                agsMap.load()
            } catch (exception: Exception) {
                Log.d("MainViewModel", "Map load failed. (${exception.cause?.localizedMessage})")
            }
            return@withContext agsMap.loadStatus
        }

    // 3: Load map data and start ILDS
    private suspend fun loadMapDataAndStartIndoorsLocationDataSource(agsMap: ArcGISMap) =
        withContext(Dispatchers.Default) {
            if (loadLocationSourceFeatureTables(agsMap) != LoadStatus.LOADED) {
                Log.d("MainViewModel", "Failed to load IPS tables.")
                showError.postValue(R.string.error_load_ips_tables)
                return@withContext
            }

            try {
                agsMap.floorManager?.load()
            } catch (exception: Exception) {
                Log.w("MainViewModel", exception.localizedMessage ?: exception.stackTraceToString())
            }

            loadIndoorsLocationDataSource(agsMap)
        }

    private suspend fun loadLocationSourceFeatureTables(agsMap: ArcGISMap): LoadStatus =
        withContext(Dispatchers.Default) {
            return@withContext try {
                // Load positioning table
                agsMap.tables?.forEach { table ->
                    if (table.loadStatus != LoadStatus.LOADED) {
                        table.load()
                    }
                }
                LoadStatus.LOADED
            } catch (e: Exception) {
                Log.d(
                    "MainViewModel",
                    "Fails to load positioning table: ${e.cause?.localizedMessage}"
                )
                LoadStatus.FAILED_TO_LOAD
            }
        }

    // 4: Select IPS_Positioning table and
    private suspend fun loadIndoorsLocationDataSource(agsMap: ArcGISMap) =
        withContext(Dispatchers.Default) {
            val positioningTable = getIPSPositioningTable(agsMap)
            if (positioningTable == null) {
                showError.postValue(R.string.error_no_ips_supported)
                return@withContext
            }

            when (val result =
                selectPositioningData(positioningTable, getPathwaysFeatureTable(agsMap))) {
                is LoadPositioningDataResult.Loaded -> {
                    startIndoorsLocationDataSource.postValue(result.ildsModel)
                }
                is LoadPositioningDataResult.Failed -> {
                    showError.postValue(result.errorMessage)
                }
            }
        }

    sealed class LoadPositioningDataResult {
        data class Loaded(val ildsModel: IndoorsLocationDataSourceModel) :
            LoadPositioningDataResult()

        data class Failed(val errorMessage: Int) :
            LoadPositioningDataResult()
    }

    private fun getPathwaysFeatureTable(agsMap: ArcGISMap): ArcGISFeatureTable? {
        val featureLayer =
            agsMap.operationalLayers?.filter { opLayer -> opLayer.name.equals("Pathways", true) }
                ?.map { opLayer -> opLayer as FeatureLayer }?.firstOrNull()

        return featureLayer?.featureTable as? ArcGISFeatureTable
    }

    private fun getIPSPositioningTable(agsMap: ArcGISMap): ServiceFeatureTable? {
        return agsMap.tables?.find { table ->
            table.tableName.equals(
                ipsPositioningTableName,
                true
            )
        } as? ServiceFeatureTable
    }

    private suspend fun selectPositioningData(
        positioningTable: ServiceFeatureTable,
        pathwaysTable: ArcGISFeatureTable?
    ): LoadPositioningDataResult =
        withContext(Dispatchers.Default) {
            try {
                val queryParameters = QueryParameters()
                queryParameters.maxFeatures = 1
                queryParameters.whereClause = "1 = 1"

                val orderByFields = queryParameters.orderByFields
                val dateCreatedFieldName = getDateCreatedFieldName(positioningTable.fields)
                orderByFields.add(
                    QueryParameters.OrderBy(
                        dateCreatedFieldName,
                        QueryParameters.SortOrder.DESCENDING
                    )
                )

                val result = positioningTable.queryFeaturesAsync(queryParameters).await()
                val featureIterator = result.iterator()
                val feature = if (featureIterator.hasNext()) {
                    featureIterator.next()
                } else {
                    // positioningTable has no entries
                    return@withContext LoadPositioningDataResult.Failed(R.string.error_no_ips_supported)
                }

                // The ID that identifies a row in the positioning table.
                val positioningId = feature.attributes[positioningTable.globalIdField] as UUID

                // Setting up IndoorsLocationDataSource with positioning, pathways tables and positioning ID.

                // positioningTable - the "IPS_Positioning" feature table from an IPS-enabled map.

                // pathwaysTable - An ArcGISFeatureTable that contains pathways as per the ArcGIS Indoors Information Model.
                //   Setting this property enables path snapping of locations provided by the IndoorsLocationDataSource.

                // positioningID - an ID which identifies a specific row in the positioningTable that should be used for setting up IPS.
                return@withContext LoadPositioningDataResult.Loaded(
                    IndoorsLocationDataSourceModel(
                        positioningTable,
                        pathwaysTable,
                        positioningId
                    )
                )
            } catch (ex: Exception) {
                return@withContext LoadPositioningDataResult.Failed(R.string.error_no_ips_supported)
            }
        }

    private fun getDateCreatedFieldName(fields: List<Field>): String {
        val field = fields
            .firstOrNull { field ->
                field.name.equals("DateCreated", ignoreCase = true)
                        || field.name.equals("Date_Created", ignoreCase = true)
            }
        return field?.name ?: ""
    }

    fun startIndoorsLocationDataSource(agsMap: ArcGISMap) {
        viewModelScope.launch {
            loadIndoorsLocationDataSource(agsMap)
        }
    }

    fun updateFloor(agsMap: ArcGISMap, floor: Int) {
        if (currentFloor == floor) return

        currentFloor = floor

        // Use ArcGIS FloorManager to set visibility of levels
        agsMap.floorManager?.let {
            it.levels.forEach { level ->
                level.isVisible = level.verticalOrder == floor
            }
        }
    }

    fun reset() {
        currentFloor = null
    }
}

data class IndoorsLocationDataSourceModel(
    val positioningTable: FeatureTable? = null,
    val pathwaysTable: ArcGISFeatureTable? = null,
    val positioningId: UUID? = null
)