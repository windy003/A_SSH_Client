package com.sshclient

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sshclient.data.AppDatabase
import com.sshclient.data.Connection
import com.sshclient.databinding.ActivityMainBinding
import com.sshclient.ui.ConnectionAdapter
import com.sshclient.viewmodel.MainViewModel
import com.sshclient.viewmodel.MainViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(AppDatabase.getInstance(this).connectionDao())
    }
    private lateinit var adapter: ConnectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = ConnectionAdapter(
            onConnect = { connection -> openTerminal(connection) },
            onEdit = { connection -> openAddConnection(connection) },
            onDelete = { connection -> confirmDelete(connection) }
        )
        binding.recyclerView.adapter = adapter

        lifecycleScope.launch {
            viewModel.connections.collect { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty())
                    android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        binding.fab.setOnClickListener { openAddConnection(null) }
    }

    private fun openTerminal(connection: Connection) {
        val intent = Intent(this, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_CONNECTION_ID, connection.id)
        }
        startActivity(intent)
    }

    private fun openAddConnection(connection: Connection?) {
        val intent = Intent(this, AddConnectionActivity::class.java).apply {
            connection?.let { putExtra(AddConnectionActivity.EXTRA_CONNECTION_ID, it.id) }
        }
        startActivity(intent)
    }

    private fun confirmDelete(connection: Connection) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_connection)
            .setMessage(getString(R.string.delete_confirm, connection.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch { viewModel.delete(connection) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
