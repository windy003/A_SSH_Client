package com.sshclient.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sshclient.data.Connection
import com.sshclient.databinding.ItemConnectionBinding

class ConnectionAdapter(
    private val onConnect: (Connection) -> Unit,
    private val onEdit: (Connection) -> Unit,
    private val onDelete: (Connection) -> Unit
) : ListAdapter<Connection, ConnectionAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(private val binding: ItemConnectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(connection: Connection) {
            binding.tvName.text = connection.name
            binding.tvHost.text = "${connection.username}@${connection.host}:${connection.port}"
            binding.root.setOnClickListener { onConnect(connection) }
            binding.btnEdit.setOnClickListener { onEdit(connection) }
            binding.btnDelete.setOnClickListener { onDelete(connection) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConnectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<Connection>() {
        override fun areItemsTheSame(oldItem: Connection, newItem: Connection) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Connection, newItem: Connection) = oldItem == newItem
    }
}
