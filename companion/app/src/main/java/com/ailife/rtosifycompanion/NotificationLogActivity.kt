package com.ailife.rtosifycompanion

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.appbar.AppBarLayout

class NotificationLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NotificationLogAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_log)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        val appBarLayout = findViewById<AppBarLayout>(R.id.appBarLayout)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.recyclerViewLog)
        tvEmpty = findViewById(R.id.tvEmpty)

        recyclerView.layoutManager = LinearLayoutManager(this)
        NotificationLogManager.init(this)
        adapter = NotificationLogAdapter(NotificationLogManager.getLogs())
        recyclerView.adapter = adapter

        updateVisibility()
        
        EdgeToEdgeUtils.applyEdgeToEdgeWithToolbar(this, appBarLayout, recyclerView)
    }

    private fun updateVisibility() {
        if (adapter.itemCount == 0) {
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_notification_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_clear_log -> {
                NotificationLogManager.clearLogs(this)
                adapter.updateLogs(emptyList())
                updateVisibility()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
