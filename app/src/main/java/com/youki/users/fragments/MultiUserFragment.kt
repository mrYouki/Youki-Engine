package com.youki.users.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.youki.users.R
import com.youki.users.adapters.UserAdapter
import com.youki.users.utils.MultiUserManager
import com.youki.users.utils.MultiUserManager.YoukiUser
import com.youki.users.utils.RootManager
import com.youki.users.utils.ShizukuManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The whole app: list users, create a user (with a photo), switch, delete,
 * and change a user's photo. Needs Shizuku or root.
 */
class MultiUserFragment : Fragment() {

    private sealed class UiState {
        object Loading     : UiState()
        object NoPrivilege : UiState()
        object EmptyResult : UiState() // has Shizuku/root but the list came back empty
        data class Success(val users: List<YoukiUser>) : UiState()
    }

    private lateinit var usersRv: RecyclerView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var emptyView: View
    private lateinit var emptyTitleTv: TextView
    private lateinit var emptySubtitleTv: TextView
    private lateinit var emptyDiagnoseBtn: MaterialButton
    private lateinit var addUserFab: ExtendedFloatingActionButton
    private lateinit var privilegeBadge: TextView
    private lateinit var usersCountTv: TextView

    private val adapter = UserAdapter(
        onSwitch      = ::confirmSwitch,
        onDelete      = ::confirmDelete,
        onAvatarClick = ::pickAvatarFor
    )

    private lateinit var shizuku: ShizukuManager
    private var fetchJob: Job? = null

    // ─────────────────────────────────────────────────────────────
    //  Image picker (photo or GIF). One launcher, the caller sets what to do with the result.
    // ─────────────────────────────────────────────────────────────

    private var onImagePicked: ((Uri) -> Unit)? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = onImagePicked
        onImagePicked = null
        if (uri != null && cb != null) cb(uri)
    }

    private fun launchImagePicker(onPicked: (Uri) -> Unit) {
        onImagePicked = onPicked
        pickImage.launch("image/*")
    }

    /**
     * Copies the picked image into our cache right away, so we don't depend on the
     * picker's temporary read grant still being valid when the person finally taps "Create".
     */
    private suspend fun stagePickedImage(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val ctx = context ?: return@withContext null
            val out = File(ctx.cacheDir, "pending_avatar_${System.nanoTime()}")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            Uri.fromFile(out)
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_multi_user, container, false)

        usersRv          = v.findViewById(R.id.users_rv)
        progressBar      = v.findViewById(R.id.multi_user_progress)
        emptyView        = v.findViewById(R.id.empty_view)
        emptyTitleTv     = v.findViewById(R.id.empty_title_tv)
        emptySubtitleTv  = v.findViewById(R.id.empty_subtitle_tv)
        emptyDiagnoseBtn = v.findViewById(R.id.empty_diagnose_btn)
        addUserFab       = v.findViewById(R.id.add_user_fab)
        privilegeBadge   = v.findViewById(R.id.privilege_badge)
        usersCountTv     = v.findViewById(R.id.users_count_tv)

        emptyDiagnoseBtn.setOnClickListener { showDiagnosticsDialog() }
        shizuku = ShizukuManager.getInstance(requireContext())

        usersRv.layoutManager = LinearLayoutManager(requireContext())
        usersRv.adapter = adapter
        usersRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) addUserFab.shrink() else if (dy < -8) addUserFab.extend()
            }
        })

        shizuku.addOnBoundListener("muf_badge") { updatePrivilegeBadge() }
        shizuku.addOnGrantedListener("muf") { updatePrivilegeBadge(); refreshUsers() }
        shizuku.addOnDeniedListener("muf")  { updatePrivilegeBadge() }
        shizuku.addOnUnboundListener("muf") { updatePrivilegeBadge() }

        addUserFab.setOnClickListener { onAddUserClicked() }
        return v
    }

    override fun onResume() {
        super.onResume()
        updatePrivilegeBadge()
        if (shizuku.isAvailable && !shizuku.hasPermission) shizuku.requestPermission()
        refreshUsers()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()
        fetchJob = null
        shizuku.removeOnBoundListener("muf_badge")
        shizuku.removeOnGrantedListener("muf")
        shizuku.removeOnDeniedListener("muf")
        shizuku.removeOnUnboundListener("muf")
    }

    // ─────────────────────────────────────────────────────────────
    //  Refresh
    // ─────────────────────────────────────────────────────────────

    private fun refreshUsers() {
        if (view == null) return
        fetchJob?.cancel()
        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            applyState(UiState.Loading)
            val users = try {
                MultiUserManager.listUsers(requireContext())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (!isActive) return@launch
            applyState(
                when {
                    users.isNotEmpty() -> UiState.Success(users)
                    MultiUserManager.hasPrivilege(requireContext()) -> UiState.EmptyResult
                    else -> UiState.NoPrivilege
                }
            )
        }
    }

    private fun applyState(state: UiState) {
        if (view == null) return
        progressBar.visibility = if (state is UiState.Loading) View.VISIBLE else View.GONE
        addUserFab.isEnabled   = state !is UiState.Loading

        when (state) {
            is UiState.Loading -> {
                emptyView.visibility = View.GONE
                usersCountTv.text    = getString(R.string.loading_ellipsis)
            }
            is UiState.NoPrivilege -> {
                emptyView.visibility        = View.VISIBLE
                emptyTitleTv.text           = getString(R.string.cannot_fetch_users)
                emptySubtitleTv.text        = getString(R.string.requires_shizuku_or_root)
                emptyDiagnoseBtn.visibility = View.GONE
                adapter.submitList(emptyList())
                usersCountTv.text = ""
            }
            is UiState.EmptyResult -> {
                emptyView.visibility        = View.VISIBLE
                emptyTitleTv.text           = getString(R.string.could_not_read_users)
                emptySubtitleTv.text        = getString(R.string.pm_list_users_no_valid_data)
                emptyDiagnoseBtn.visibility = View.VISIBLE
                adapter.submitList(emptyList())
                usersCountTv.text = ""
            }
            is UiState.Success -> {
                emptyView.visibility        = View.GONE
                emptyDiagnoseBtn.visibility = View.GONE
                adapter.submitList(state.users)
                usersCountTv.text = when (state.users.size) {
                    1    -> getString(R.string.one_user_on_device)
                    else -> getString(R.string.n_users_on_device, state.users.size)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Switch / delete / change photo
    // ─────────────────────────────────────────────────────────────

    private fun confirmSwitch(user: YoukiUser) = confirm(
        getString(R.string.switch_user), getString(R.string.switch_to_user_confirm, user.name)
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            applyState(UiState.Loading)
            val (_, msg) = MultiUserManager.switchToUser(requireContext(), user.id)
            showSnack(msg)
            refreshUsers()
        }
    }

    private fun confirmDelete(user: YoukiUser) = confirm(
        getString(R.string.delete_user), getString(R.string.delete_user_confirm, user.name)
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            applyState(UiState.Loading)
            val (ok, msg) = MultiUserManager.removeUser(requireContext(), user.id)
            showSnack(msg)
            if (ok) MultiUserManager.deleteUserAvatar(requireContext(), user.id)
            refreshUsers()
        }
    }

    private fun pickAvatarFor(user: YoukiUser) {
        if (!MultiUserManager.hasPrivilege(requireContext())) {
            showSnack(getString(R.string.privilege_required_change_avatar))
            return
        }
        launchImagePicker { picked ->
            viewLifecycleOwner.lifecycleScope.launch {
                val staged = stagePickedImage(picked)
                if (staged == null) { showSnack(getString(R.string.avatar_load_failed)); return@launch }
                val (_, message) = MultiUserManager.applyPickedAvatar(requireContext(), user.id, staged)
                staged.path?.let { File(it).delete() }
                showSnack(message)
                refreshUsers()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Create user (name + optional photo)
    // ─────────────────────────────────────────────────────────────

    private fun onAddUserClicked() {
        if (!MultiUserManager.hasPrivilege(requireContext())) {
            showSnack(getString(R.string.privilege_required_create_user))
            return
        }

        val dv         = layoutInflater.inflate(R.layout.dialog_create_user, null)
        val et         = dv.findViewById<TextInputEditText>(R.id.create_user_name_et)
        val avatarWrap = dv.findViewById<FrameLayout>(R.id.create_avatar_wrapper)
        val avatarIv   = dv.findViewById<ShapeableImageView>(R.id.create_avatar_iv)
        val avatarIcon = dv.findViewById<ImageView>(R.id.create_avatar_icon)
        val hintTv     = dv.findViewById<TextView>(R.id.create_avatar_hint)

        var stagedAvatar: Uri? = null

        avatarWrap.setOnClickListener {
            launchImagePicker { picked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val staged = stagePickedImage(picked)
                    if (staged == null) { showSnack(getString(R.string.avatar_load_failed)); return@launch }
                    stagedAvatar?.path?.let { File(it).delete() }
                    stagedAvatar = staged
                    avatarIv.setImageURI(staged) // preview (a GIF shows its first frame here)
                    avatarIv.visibility   = View.VISIBLE
                    avatarIcon.visibility = View.GONE
                    avatarWrap.background = null // no tinted circle behind a transparent photo
                    hintTv.text = getString(R.string.tap_to_change_photo)
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.new_user))
            .setView(dv)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = et.text?.toString()?.trim()
                if (name.isNullOrBlank()) {
                    stagedAvatar?.path?.let { File(it).delete() }
                    showSnack(getString(R.string.enter_user_name))
                    return@setPositiveButton
                }
                launchCreate(name, stagedAvatar)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                stagedAvatar?.path?.let { File(it).delete() }
            }
            .setOnCancelListener { stagedAvatar?.path?.let { File(it).delete() } }
            .show()
    }

    private fun launchCreate(name: String, avatar: Uri?) {
        val ctx = requireContext()
        val dv  = layoutInflater.inflate(R.layout.dialog_creating_user, null)
        val stepTv = dv.findViewById<TextView>(R.id.creating_step_tv)
        stepTv.text = getString(R.string.creating_user_named, name)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.new_user))
            .setView(dv)
            .setCancelable(false)
            .create()
            .also { it.show() }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = MultiUserManager.createUser(ctx, name, avatar)
            avatar?.path?.let { File(it).delete() }
            dialog.dismiss()

            val message = buildString {
                append(result.message)
                if (result.ok && result.avatarMessage != null) {
                    append("\n\n").append(result.avatarMessage)
                }
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(if (result.ok) getString(R.string.completed_successfully) else getString(R.string.failed))
                .setMessage(message)
                .setPositiveButton(getString(R.string.ok), null)
                .setOnDismissListener { if (view != null) refreshUsers() }
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Privilege badge / diagnostics / helpers
    // ─────────────────────────────────────────────────────────────

    private fun updatePrivilegeBadge() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        privilegeBadge.text = when {
            shizuku.hasPermission                    -> "Shizuku"
            RootManager.getInstance(ctx).isAvailable -> "Root"
            shizuku.isAvailable                      -> "Shizuku (${getString(R.string.no_permission)})"
            else                                     -> getString(R.string.without_permissions)
        }
    }

    private fun showDiagnosticsDialog() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { MultiUserManager.diagnoseUserListing(ctx) }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val tv = TextView(ctx).apply {
                text = report
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(pad, pad, pad, pad)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(getString(R.string.user_diagnostics))
                .setView(ScrollView(ctx).apply { addView(tv) })
                .setPositiveButton(getString(R.string.copy_report)) { _, _ ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("diagnostics", report))
                    showSnack(getString(R.string.report_copied))
                }
                .setNeutralButton(getString(R.string.retry)) { _, _ -> refreshUsers() }
                .setNegativeButton(getString(R.string.close), null)
                .show()
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.confirm)) { _, _ -> action() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

    private fun showSnack(msg: String) =
        view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_LONG).show() }
}
