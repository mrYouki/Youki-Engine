package com.youki.users.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.youki.users.R
import com.youki.users.utils.AvatarDisplay
import com.youki.users.utils.MultiUserManager.YoukiUser

/**
 * UserAdapter — v2 (Magisk-style card)
 *
 * Same philosophy as buildMagiskCard in PluginStoreFragment:
 *  - a primaryContainer circle for the icon/photo
 *  - an info column (name + ID + badges)
 *  - tapping the whole card = the primary action (switch)
 *  - a pill button bottom-right + an IconButton for delete
 */
class UserAdapter(
    private val onSwitch: (YoukiUser) -> Unit,
    private val onDelete: (YoukiUser) -> Unit,
    private val onAvatarClick: (YoukiUser) -> Unit
) : ListAdapter<YoukiUser, UserAdapter.UserViewHolder>(DiffCallback()) {

    // ─────────────────────────────────────────────────────────────
    //  ViewHolder
    // ─────────────────────────────────────────────────────────────

    inner class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatarWrapper: FrameLayout      = view.findViewById(R.id.user_avatar_wrapper)
        private val avatarIv: ShapeableImageView    = view.findViewById(R.id.user_avatar_iv)
        private val avatarIcon: View                = view.findViewById(R.id.user_avatar_icon)
        private val nameTv: TextView                = view.findViewById(R.id.user_name_tv)
        private val idTv: TextView                  = view.findViewById(R.id.user_id_tv)
        private val badgesRow: View                 = view.findViewById(R.id.user_badges_row)
        private val currentBadge: TextView          = view.findViewById(R.id.user_current_badge)
        private val runningBadge: TextView          = view.findViewById(R.id.user_running_badge)
        private val switchBtn: MaterialButton       = view.findViewById(R.id.user_switch_btn)
        private val deleteBtn: MaterialButton       = view.findViewById(R.id.user_delete_btn)

        fun bind(user: YoukiUser) {
            val ctx = itemView.context

            // ── Basic info
            nameTv.text = user.name
            idTv.text   = "ID ${user.id}"

            // ── The badges row (You / Active) — only shown if either one exists
            val showCurrent = user.isCurrentUser
            val showRunning = user.isRunning
            badgesRow.visibility   = if (showCurrent || showRunning) View.VISIBLE else View.GONE
            currentBadge.visibility = if (showCurrent) View.VISIBLE else View.GONE
            runningBadge.visibility = if (showRunning) View.VISIBLE else View.GONE

            // ── The photo / default icon
            loadAvatar(ctx, user)
            avatarWrapper.setOnClickListener { onAvatarClick(user) }

            // ── The switch button (Pill) — disabled for the current user
            val isCurrent = user.isCurrentUser
            switchBtn.isEnabled = !isCurrent
            switchBtn.alpha     = if (isCurrent) 0.4f else 1f
            switchBtn.setOnClickListener { onSwitch(user) }

            // ── The delete button — hidden for user 0 (the primary user)
            if (user.id == 0) {
                deleteBtn.visibility = View.GONE
            } else {
                deleteBtn.visibility = View.VISIBLE
                deleteBtn.setOnClickListener { onDelete(user) }
            }

            // ── Tapping the whole card = switch (same behavior as the plugin card)
            itemView.isClickable = !isCurrent
            itemView.setOnClickListener {
                if (!isCurrent) onSwitch(user)
            }
        }

        private fun loadAvatar(ctx: Context, user: YoukiUser) {
            // GIF if the user has one, else the saved PNG, else the system icon
            val shown = AvatarDisplay.showOn(avatarIv, ctx, user.id)
            avatarIv.visibility   = if (shown) View.VISIBLE else View.GONE
            avatarIcon.visibility = if (shown) View.GONE else View.VISIBLE
            // The tinted circle is only the placeholder: with a photo it would show through
            // transparent areas (transparent PNG/GIF), so remove it.
            avatarWrapper.setBackgroundResource(if (shown) 0 else R.drawable.circle_primary_container)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Adapter overrides
    // ─────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_card, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // ─────────────────────────────────────────────────────────────
    //  DiffUtil
    // ─────────────────────────────────────────────────────────────

    private class DiffCallback : DiffUtil.ItemCallback<YoukiUser>() {
        override fun areItemsTheSame(a: YoukiUser, b: YoukiUser): Boolean = a.id == b.id
        override fun areContentsTheSame(a: YoukiUser, b: YoukiUser): Boolean = a == b
    }
}
