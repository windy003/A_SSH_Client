package com.sshclient

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sshclient.data.AppDatabase
import com.sshclient.data.Connection
import com.sshclient.databinding.ActivityAddConnectionBinding
import kotlinx.coroutines.launch

class AddConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddConnectionBinding
    private var connectionId: Long = -1L

    companion object {
        const val EXTRA_CONNECTION_ID = "extra_connection_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)

        if (connectionId != -1L) {
            supportActionBar?.title = getString(R.string.edit_connection)
            loadConnection(connectionId)
        } else {
            supportActionBar?.title = getString(R.string.add_connection)
            binding.etPort.setText("22")
        }

        binding.btnSave.setOnClickListener { saveConnection() }
    }

    private fun loadConnection(id: Long) {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@AddConnectionActivity)
            db.connectionDao().getById(id)?.let { conn ->
                binding.etName.setText(conn.name)
                binding.etHost.setText(conn.host)
                binding.etPort.setText(conn.port.toString())
                binding.etUsername.setText(conn.username)
                binding.etPassword.setText(conn.password)
                binding.etPrivateKey.setText(conn.privateKey)
            }
        }
    }

    private fun saveConnection() {
        val name = binding.etName.text.toString().trim()
        val host = binding.etHost.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val privateKey = binding.etPrivateKey.text.toString().trim()

        if (name.isEmpty() || host.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, R.string.fill_required_fields, Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
            return
        }

        if (password.isEmpty() && privateKey.isEmpty()) {
            Toast.makeText(this, R.string.auth_required, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@AddConnectionActivity).connectionDao()
            val connection = Connection(
                id = if (connectionId != -1L) connectionId else 0,
                name = name,
                host = host,
                port = port,
                username = username,
                password = password,
                privateKey = privateKey
            )
            if (connectionId != -1L) dao.update(connection) else dao.insert(connection)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
