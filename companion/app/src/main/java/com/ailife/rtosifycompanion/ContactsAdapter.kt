package com.ailife.rtosifycompanion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ContactEntry(
    val name: String,
    val phoneNumber: String
)

class ContactsAdapter(
    private val contacts: List<ContactEntry>,
    private val onContactSelected: (ContactEntry) -> Unit,
    private val onCallClicked: (ContactEntry) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        holder.bind(contact)
    }

    override fun getItemCount() = contacts.size

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvContactName)
        private val tvNumber: TextView = itemView.findViewById(R.id.tvContactNumber)
        private val imgCall: ImageView = itemView.findViewById(R.id.imgCallAction)

        fun bind(contact: ContactEntry) {
            tvName.text = contact.name
            tvNumber.text = contact.phoneNumber

            itemView.setOnClickListener { onContactSelected(contact) }
            imgCall.setOnClickListener { onCallClicked(contact) }
        }
    }
}
