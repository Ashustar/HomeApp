package io.github.domi04151309.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.android.material.navigation.NavigationView
import androidx.core.view.GravityCompat
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.*
import io.github.domi04151309.home.simplehome.SimpleHomeAPI
import io.github.domi04151309.home.hue.HueAPI
import io.github.domi04151309.home.hue.HueLampActivity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import android.widget.TextView
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import io.github.domi04151309.home.data.DeviceItem
import io.github.domi04151309.home.data.ListViewItem
import io.github.domi04151309.home.data.RequestCallbackObject
import io.github.domi04151309.home.helpers.Commands
import io.github.domi04151309.home.helpers.Devices
import io.github.domi04151309.home.helpers.ListViewAdapter
import io.github.domi04151309.home.helpers.UpdateHandler
import io.github.domi04151309.home.objects.Global
import io.github.domi04151309.home.objects.Theme
import io.github.domi04151309.home.tasmota.Tasmota

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private var currentDevice = ""
    private var tasmotaPosition: Int = 0
    private var level = "one"
    private var reset : Boolean = false
    private val updateHandler = UpdateHandler()
    private var canReceiveRequest = false
    internal var currentView: View? = null
    internal var hueRoom: String = ""
    internal var hueRoomState: Boolean = false
    internal var hueCurrentIcon: Int = 0
    internal lateinit var devices: Devices
    internal lateinit var listView: ListView
    private lateinit var deviceIcon: ImageView
    private lateinit var deviceName: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    internal val hueGroupStateListener = CompoundButton.OnCheckedChangeListener { compoundButton, b ->
        if (compoundButton.isPressed) {
            val hidden = (compoundButton.parent as ViewGroup).findViewById<TextView>(R.id.hidden).text
            hueAPI?.switchGroupByID(hidden.substring(hidden.lastIndexOf("#") + 1), b)
        }
    }

    internal val hueLampStateListener = CompoundButton.OnCheckedChangeListener { compoundButton, b ->
        if (compoundButton.isPressed) {
            val hidden = (compoundButton.parent as ViewGroup).findViewById<TextView>(R.id.hidden).text.toString()
            if (hidden.startsWith("room#")) {
                hueAPI?.switchGroupByID(hidden.substring(hidden.lastIndexOf("#") + 1), b)
            } else {
                hueAPI?.switchLightByID(hidden, b)
            }
        }
    }

    /*
     * Things related to the Home API
     */
    private val homeAPI = SimpleHomeAPI(this)

    private val homeRequestCallBack = object : SimpleHomeAPI.RequestCallBack {

        override fun onExecutionFinished(context: Context, result: CharSequence) {
            Toast.makeText(context, result, Toast.LENGTH_LONG).show()
        }

        override fun onCommandsLoaded(holder: RequestCallbackObject) {
            if (holder.response != null) {
                val commandsObject = Commands(holder.response.getJSONObject("commands"))
                val listItems: ArrayList<ListViewItem> = ArrayList(commandsObject.length())
                for (i in 0 until commandsObject.length()) {
                    try {
                        commandsObject.selectCommand(i)
                        listItems += ListViewItem(
                                title = commandsObject.getSelectedTitle(),
                                summary = commandsObject.getSelectedSummary(),
                                hidden = devices.getDeviceById(holder.deviceId).address + commandsObject.getSelected(),
                                icon = R.drawable.ic_do
                        )
                    } catch (e: JSONException) {
                        Log.e(Global.LOG_TAG, e.toString())
                    }
                }
                updateList(listItems)
                val device = devices.getDeviceById(holder.deviceId)
                setLevelTwo(device.iconId, device.name)
            } else {
                handleErrorOnLevelOne(holder.errorMessage)
            }
        }
    }

    /*
     * Things related to the Hue API
     */
    private var hueAPI: HueAPI? = null

    private val hueRequestCallBack = object : HueAPI.RequestCallBack {

        override fun onGroupLoaded(holder: RequestCallbackObject) {}

        override fun onGroupsLoaded(holder: RequestCallbackObject) {
            if (holder.response != null) {
                try {
                    var currentObjectName: String
                    var currentObject: JSONObject
                    var type: String
                    val listItems: ArrayList<ListViewItem> = ArrayList(holder.response.length())
                    for (i in 0 until holder.response.length()) {
                        try {
                            currentObjectName = holder.response.names()?.getString(i) ?: ""
                            currentObject = holder.response.getJSONObject(currentObjectName)
                            type = currentObject.getString("type")
                            listItems += ListViewItem(
                                    title = currentObject.getString("name"),
                                    summary = resources.getString(R.string.hue_tap),
                                    hidden = "${currentObject.getJSONArray("lights")}@${if (type == "Room") "room" else "zone"}#$currentObjectName",
                                    icon = if (type == "Room") R.drawable.ic_room else R.drawable.ic_zone,
                                    state = currentObject.getJSONObject("action").getBoolean("on"),
                                    stateListener = hueGroupStateListener
                            )
                        } catch (e: JSONException) {
                            Log.e(Global.LOG_TAG, e.toString())
                        }
                    }
                    updateList(listItems)
                    val device = devices.getDeviceById(holder.deviceId)
                    hueCurrentIcon = device.iconId
                    setLevelTwoHue(holder.deviceId, device.iconId, device.name)
                } catch (e: Exception) {
                    handleErrorOnLevelOne(resources.getString(R.string.err_wrong_format_summary))
                    Log.e(Global.LOG_TAG, e.toString())
                }
            } else {
                handleErrorOnLevelOne(holder.errorMessage)
            }
        }

        override fun onLightsLoaded(holder: RequestCallbackObject) {
            if (holder.response != null) {
                try {
                    val roomItem = ListViewItem(
                            title = if (holder.forZone) resources.getString(R.string.hue_whole_zone) else resources.getString(R.string.hue_whole_room),
                            summary = if (holder.forZone) resources.getString(R.string.hue_whole_zone_summary) else resources.getString(R.string.hue_whole_room_summary),
                            hidden = "room#$hueRoom",
                            icon = if (holder.forZone) R.drawable.ic_zone else R.drawable.ic_room,
                            state = hueRoomState,
                            stateListener = hueLampStateListener
                    )
                    var currentObjectName: String
                    var currentObject: JSONObject
                    val listItems: ArrayList<ListViewItem> = arrayListOf(roomItem)
                    for (i in 1 until (holder.response.length() + 1)) {
                        try {
                            currentObjectName = holder.response.names()?.getString(i - 1) ?: ""
                            currentObject = holder.response.getJSONObject(currentObjectName)
                            listItems += ListViewItem(
                                    title = currentObject.getString("name"),
                                    summary = resources.getString(R.string.hue_tap),
                                    hidden = currentObjectName,
                                    icon = R.drawable.ic_device_lamp,
                                    state = currentObject.getJSONObject("state").getBoolean("on"),
                                    stateListener = hueLampStateListener
                            )
                        } catch (e: JSONException) {
                            Log.e(Global.LOG_TAG, e.toString())
                        }
                    }
                    updateList(listItems)
                    setLevelThreeHue(currentView?.findViewById<TextView>(R.id.title)?.text ?: "")
                } catch (e: Exception) {
                    setLevelTwo(hueCurrentIcon, devices.getDeviceById(holder.deviceId).name)
                    currentView?.findViewById<TextView>(R.id.summary)?.text = resources.getString(R.string.err_wrong_format_summary)
                    Log.e(Global.LOG_TAG, e.toString())
                }
            } else {
                setLevelTwoHue(holder.deviceId, hueCurrentIcon, devices.getDeviceById(holder.deviceId).name)
                currentView?.findViewById<TextView>(R.id.summary)?.text = holder.errorMessage
            }
        }
    }
    private val hueRequestUpdaterCallBack = object : HueAPI.RequestCallBack {

        override fun onGroupLoaded(holder: RequestCallbackObject) {
            if (holder.response != null) {
                try {
                    (listView.adapter as ListViewAdapter).updateSwitch(0, holder.response
                            .getJSONObject("state")
                            .getBoolean("any_on")
                    )
                } catch (e: Exception) {
                    Log.e(Global.LOG_TAG, e.toString())
                }
            }
        }

        override fun onGroupsLoaded(holder: RequestCallbackObject) {
            if (holder.response != null) {
                try {
                    for (i in 0 until holder.response.length()) {
                        (listView.adapter as ListViewAdapter).updateSwitch(i, holder.response
                                .getJSONObject(holder.response.names()?.getString(i) ?: "")
                                .getJSONObject("state")
                                .getBoolean("any_on")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(Global.LOG_TAG, e.toString())
                }
            }
        }

        override fun onLightsLoaded(holder: RequestCallbackObject) {
            if (holder.response != null) {
                try {
                    for (i in 1 until (holder.response.length() + 1)) {
                        (listView.adapter as ListViewAdapter).updateSwitch(i, holder.response
                                .getJSONObject(holder.response.names()?.getString(i - 1) ?: "")
                                .getJSONObject("state")
                                .getBoolean("on")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(Global.LOG_TAG, e.toString())
                }
            }
        }
    }

    /*
     * Things related to Tasmota
     */
    internal var tasmota: Tasmota? = null
    private val tasmotaRequestCallBack = object : Tasmota.RequestCallBack {

        override fun onItemsChanged(context: Context) {
            listView.adapter = ListViewAdapter(context, tasmota?.loadList() ?: arrayListOf(), false)
        }

        override fun onResponse(context: Context, response: String) {
            Snackbar.make(listView, R.string.main_execution_completed, Snackbar.LENGTH_LONG).setAction(R.string.str_show) {
                AlertDialog.Builder(context)
                        .setTitle(R.string.main_execution_completed)
                        .setMessage(response)
                        .setPositiveButton(android.R.string.ok) { _, _ -> }
                        .show()
            }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.setNoActionBar(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devices = Devices(this)
        listView = findViewById(R.id.listView)
        deviceIcon = findViewById(R.id.deviceIcon)
        deviceName = findViewById(R.id.deviceName)
        fab = findViewById(R.id.fab)
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)

        fab.setOnClickListener {
            reset = true
            startActivity(Intent(this, DevicesActivity::class.java))
        }

        findViewById<ImageView>(R.id.menu_icon).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        navView.setNavigationItemSelectedListener(this)
        navView.setCheckedItem(R.id.nav_devices)

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, view, _, _ ->
            currentView = view
            val title = view.findViewById<TextView>(R.id.title).text.toString()
            if (title == resources.getString(R.string.main_no_devices) || title == resources.getString(R.string.err_wrong_format)) {
                reset = true
                startActivity(Intent(this, DevicesActivity::class.java))
                return@OnItemClickListener
            }
            val hidden = view.findViewById<TextView>(R.id.hidden).text.toString()
            when (level) {
                "one" -> {
                    view.findViewById<TextView>(R.id.summary).text = resources.getString(R.string.main_connecting)
                    handleLevelOne(hidden)
                }
                "two" ->
                    homeAPI.executeCommand(hidden, homeRequestCallBack)
                "two_hue" -> {
                    hueRoom = hidden.substring(hidden.lastIndexOf("#") + 1)
                    hueRoomState = view.findViewById<Switch>(R.id.state).isChecked
                    val localIds = JSONArray(hidden.substring(0 , hidden.indexOf("@")))
                    hueAPI?.loadLightsByIDs(localIds, hueRequestCallBack, hidden.contains("zone"))
                    updateHandler.setUpdateFunction {
                        if (canReceiveRequest && hueAPI?.readyForRequest == true) {
                            hueAPI?.loadGroupByID(hueRoom, hueRequestUpdaterCallBack)
                            hueAPI?.loadLightsByIDs(localIds, hueRequestUpdaterCallBack)
                        }
                    }
                }
                "three_hue" ->
                    startActivity(Intent(this, HueLampActivity::class.java).putExtra("ID", hidden).putExtra("Device", currentDevice))
                "two_tasmota" -> {
                    when (hidden) {
                        "add" -> tasmota?.addToList(tasmotaRequestCallBack)
                        "execute_once" -> tasmota?.executeOnce(tasmotaRequestCallBack)
                        else -> tasmota?.execute(tasmotaRequestCallBack, view.findViewById<TextView>(R.id.summary).text.toString())
                    }
                }
            }
        }

        registerForContextMenu(listView)

        //Handle shortcut
        if(intent.hasExtra("device")) {
            val deviceId = intent.getStringExtra("device") ?: ""
            if (devices.idExists(deviceId)) {
                val device = devices.getDeviceById(deviceId)
                deviceIcon.setImageResource(device.iconId)
                deviceName.text = device.name
                handleLevelOne(deviceId)
            } else {
                loadDevices()
                Toast.makeText(this, R.string.main_device_nonexistent, Toast.LENGTH_LONG).show()
            }
        } else {
            loadDevices()
        }
    }

    private fun handleLevelOne(deviceId: String) {
        val deviceObj = devices.getDeviceById(deviceId)
        when (deviceObj.mode) {
            "Website" -> {
                startActivity(
                        Intent(this, WebActivity::class.java)
                                .putExtra("URI", deviceObj.address)
                                .putExtra("title", deviceObj.name)
                )
                reset = true
            }
            "SimpleHome API" ->
                homeAPI.loadCommands(deviceId, homeRequestCallBack)
            "Hue API" -> {
                hueAPI = HueAPI(this, deviceId)
                loadHueGroups()
            }
            "Tasmota" -> {
                tasmota = Tasmota(this, deviceId)
                updateList(tasmota?.loadList() ?: arrayListOf())
                setLevelTwoTasmota(deviceObj.iconId, deviceObj.name)
            }
            else ->
                Toast.makeText(this, R.string.main_unknown_mode, Toast.LENGTH_LONG).show()
        }
    }

    internal fun handleErrorOnLevelOne(err: String) {
        if (currentView == null) {
            loadDevices()
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        } else {
            setLevelOne()
            currentView?.findViewById<TextView>(R.id.summary)?.text = err
        }
    }

    private fun loadDevices() {
        updateHandler.stop()
        val listItems: ArrayList<ListViewItem> = ArrayList(devices.length())
        try {
            if (devices.length() == 0) {
                listItems += ListViewItem(
                        title = resources.getString(R.string.main_no_devices),
                        summary = resources.getString(R.string.main_no_devices_summary),
                        icon = R.drawable.ic_info
                )
            } else {
                var currentDevice: DeviceItem
                for (i in 0 until devices.length()) {
                    currentDevice = devices.getDeviceByIndex(i)
                    listItems += ListViewItem(
                            title = currentDevice.name,
                            summary = resources.getString(R.string.main_tap_to_connect),
                            hidden = currentDevice.id,
                            icon = currentDevice.iconId
                    )
                }
            }
        } catch (e: Exception) {
            listItems += ListViewItem(
                    title = resources.getString(R.string.err_wrong_format),
                    summary = resources.getString(R.string.err_wrong_format_summary),
                    hidden = "none",
                    icon = R.drawable.ic_warning
            )
            Log.e(Global.LOG_TAG, e.toString())
        }

        updateList(listItems)
        setLevelOne()
    }

    private fun loadHueGroups() {
        hueAPI?.loadGroups(hueRequestCallBack)
        updateHandler.setUpdateFunction {
            if (canReceiveRequest && hueAPI?.readyForRequest == true) {
                hueAPI?.loadGroups(hueRequestUpdaterCallBack)
            }
        }
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START))
            drawerLayout.closeDrawer(GravityCompat.START)
        else if (level == "two" || level == "two_hue" || level == "two_tasmota")
            loadDevices()
        else if (level == "three_hue")
            loadHueGroups()
        else
            super.onBackPressed()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_devices -> {
                loadDevices()
                reset = false
            }
            R.id.nav_wiki -> {
                val uri = "https://github.com/Domi04151309/HomeApp/wiki"
                startActivity(Intent(this, WebActivity::class.java).putExtra("URI", uri).putExtra("title", resources.getString(R.string.nav_wiki)))
                reset = true
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, Preferences::class.java))
                reset = true
            }
            R.id.nav_source -> {
                val uri = "https://github.com/Domi04151309/HomeApp"
                startActivity(Intent(this, WebActivity::class.java).putExtra("URI", uri).putExtra("title", resources.getString(R.string.nav_source)))
                reset = true
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onCreateContextMenu(menu: ContextMenu?, v: View?, menuInfo: ContextMenu.ContextMenuInfo?) {
        super.onCreateContextMenu(menu, v, menuInfo)
        tasmotaPosition = (menuInfo as AdapterView.AdapterContextMenuInfo).position
        if (listView.getChildAt(tasmotaPosition).findViewById<TextView>(R.id.hidden).text == "tasmota_command") {
            menuInflater.inflate(R.menu.activity_main_tasmota_context, menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        when (item.title) {
            resources.getString(R.string.str_edit) -> {
                val editing = listView.getChildAt(tasmotaPosition)
                tasmota!!.removeFromList(tasmotaRequestCallBack, tasmotaPosition)
                tasmota!!.addToList(
                        tasmotaRequestCallBack,
                        editing.findViewById<TextView>(R.id.title).text.toString(),
                        editing.findViewById<TextView>(R.id.summary).text.toString()
                )
            }
            resources.getString(R.string.str_delete) -> {
                AlertDialog.Builder(this)
                        .setTitle(R.string.str_delete)
                        .setMessage(R.string.tasmota_delete_command)
                        .setPositiveButton(R.string.str_delete) { _, _ ->
                            tasmota?.removeFromList(tasmotaRequestCallBack, tasmotaPosition)
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
            }
        }
        return super.onContextItemSelected(item)
    }

    private fun setLevelOne() {
        val theme = resources.newTheme()
        theme.applyStyle(R.style.TintHouse, false)
        deviceIcon.setImageDrawable(resources.getDrawable(R.drawable.ic_home, theme))
        deviceName.text = resources.getString(R.string.main_device_name)
        fab.show()
        level = "one"
    }

    internal fun setLevelTwo(icon: Int, title: CharSequence) {
        fab.hide()
        deviceIcon.setImageResource(icon)
        deviceName.text = title
        level = "two"
    }

    internal fun setLevelTwoHue(deviceId: String, icon: Int, title: CharSequence) {
        fab.hide()
        deviceIcon.setImageResource(icon)
        deviceName.text = title
        currentDevice = deviceId
        level = "two_hue"
    }

    private fun setLevelTwoTasmota(icon: Int, title: CharSequence) {
        fab.hide()
        deviceIcon.setImageResource(icon)
        deviceName.text = title
        level = "two_tasmota"
    }

    internal fun setLevelThreeHue(title: CharSequence) {
        deviceIcon.setImageResource(R.drawable.ic_device_lamp)
        deviceName.text = title
        level = "three_hue"
    }

    internal fun updateList(items: ArrayList<ListViewItem>) {
        listView.adapter = ListViewAdapter(this, items)
    }

    override fun onStart() {
        super.onStart()
        canReceiveRequest = true
        if(reset) {
            navView.setCheckedItem(R.id.nav_devices)
            loadDevices()
            reset = false
        }
    }

    override fun onStop() {
        super.onStop()
        canReceiveRequest = false
    }

    override fun onDestroy() {
        super.onDestroy()
        updateHandler.stop()
    }
}
