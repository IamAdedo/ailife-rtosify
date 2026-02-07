package com.ailife.rtosifycompanion

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.os.IBinder
import android.provider.ContactsContract
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.google.android.material.bottomnavigation.BottomNavigationView

class DialerActivity : AppCompatActivity() {

    private lateinit var tvNumber: TextView
    private lateinit var viewFlipper: ViewFlipper
    private lateinit var rvContacts: RecyclerView
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var etSearchContacts: EditText
    
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private var allContacts: List<ContactEntry> = emptyList()
    private var adapter: ContactsAdapter? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                loadContacts()
            } else {
                Toast.makeText(this, "Permission denied to read contacts", Toast.LENGTH_SHORT).show()
                bottomNavigationView.selectedItemId = R.id.nav_keypad
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bluetoothService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dialer)
        val rootLayout = findViewById<View>(R.id.rootLayout)
        EdgeToEdgeUtils.applyEdgeToEdge(this, rootLayout)

        tvNumber = findViewById(R.id.tv_number)
        viewFlipper = findViewById(R.id.view_flipper)
        rvContacts = findViewById(R.id.rv_contacts)
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        etSearchContacts = findViewById(R.id.et_search_contacts)
        
        rvContacts.layoutManager = LinearLayoutManager(this)

        setupBottomNavigation()
        setupSearch()

        setupKeypad()

        findViewById<Button>(R.id.btn_delete).setOnClickListener {
            val current = tvNumber.text.toString()
            if (current.isNotEmpty()) {
                tvNumber.text = current.substring(0, current.length - 1)
            }
        }

        findViewById<Button>(R.id.btn_call).setOnClickListener {
            val number = tvNumber.text.toString()
            if (number.isNotEmpty()) {
                makeCall(number)
            } else {
                Toast.makeText(this, getString(R.string.dialer_empty_number), Toast.LENGTH_SHORT).show()
            }
        }


        // Bind to BluetoothService
        val intent = Intent(this, BluetoothService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_keypad -> {
                    showKeypad()
                    true
                }
                R.id.nav_contacts -> {
                    checkPermissionAndLoadContacts()
                    true // Assume allowed or will be handled
                }
                else -> false
            }
        }
    }

    private fun setupSearch() {
        etSearchContacts.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterContacts(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterContacts(query: String) {
        val filteredList = if (query.isEmpty()) {
            allContacts
        } else {
            allContacts.filter {
                it.name.contains(query, ignoreCase = true) || it.phoneNumber.contains(query)
            }
        }
        adapter?.updateList(filteredList)
    }

    private fun showKeypad() {
        viewFlipper.displayedChild = 0
    }

    private fun showContactsView() {
        viewFlipper.displayedChild = 1
    }

    private fun checkPermissionAndLoadContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED) {
            loadContacts()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun loadContacts() {
        showContactsView()
        
        val contactList = mutableListOf<ContactEntry>()
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            
            while (it.moveToNext()) {
                val name = it.getString(nameIndex)
                val number = it.getString(numberIndex)
                contactList.add(ContactEntry(name, number))
            }
        }

        allContacts = contactList
        filterContacts(etSearchContacts.text.toString()) // Apply existing filter if any

        if (allContacts.isEmpty()) {
            Toast.makeText(this, "No contacts found", Toast.LENGTH_SHORT).show()
        }

        adapter = ContactsAdapter(
            allContacts,
            onContactSelected = { contact ->
                tvNumber.text = contact.phoneNumber
                bottomNavigationView.selectedItemId = R.id.nav_keypad
            },
            onCallClicked = { contact ->
                makeCall(contact.phoneNumber)
            }
        )
        rvContacts.adapter = adapter
    }

    private fun setupKeypad() {
        val gridLayout = findViewById<GridLayout>(R.id.gridLayoutDialer)
        for (i in 0 until gridLayout.childCount) {
            val view = gridLayout.getChildAt(i)
            if (view is Button) {
                val text = view.text.toString()
                if (text.length == 1 && (text[0].isDigit() || text[0] == '*' || text[0] == '#')) {
                    view.setOnClickListener {
                        tvNumber.append(text)
                    }
                }
            }
        }
    }

    private fun makeCall(number: String) {
        if (isBound && bluetoothService != null) {
            if (bluetoothService?.isConnected == true) {
                bluetoothService?.sendMakeCall(number)
                Toast.makeText(this, getString(R.string.dialer_calling, number), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(R.string.toast_watch_not_connected), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
