package com.emulex.gbc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.emulex.gbc.databinding.ActivityMainBinding
import com.emulex.gbc.utils.FileUtils

/**
 * Activité principale — sélection de ROM et liste des ROMs récentes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val openRomLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) launchGame(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnOpenRom.setOnClickListener {
            openRomLauncher.launch(arrayOf("*/*"))
        }

        loadRecentRoms()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_rom -> { openRomLauncher.launch(arrayOf("*/*")); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadRecentRoms() {
        val recents = FileUtils.getRecentRoms(this)
        if (recents.isEmpty()) {
            binding.tvNoRoms.visibility = android.view.View.VISIBLE
            binding.rvRecentRoms.visibility = android.view.View.GONE
            return
        }
        binding.tvNoRoms.visibility = android.view.View.GONE
        binding.rvRecentRoms.visibility = android.view.View.VISIBLE

        val adapter = RecentRomAdapter(recents) { uriString ->
            try {
                launchGame(Uri.parse(uriString))
            } catch (e: Exception) {
                Toast.makeText(this, "Impossible d'ouvrir la ROM", Toast.LENGTH_SHORT).show()
            }
        }
        binding.rvRecentRoms.adapter = adapter
        binding.rvRecentRoms.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
    }

    private fun launchGame(uri: Uri) {
        // Persister la permission de lecture
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        FileUtils.addRecentRom(this, uri.toString())

        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_ROM_URI, uri.toString())
        }
        startActivity(intent)
    }
}
